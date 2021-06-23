#include <sys/mount.h>
#include <sys/sysmacros.h>
#include <libgen.h>

#include <utils.hpp>
#include <selinux.hpp>
#include <magisk.hpp>

#include "init.hpp"

using namespace std;

struct devinfo {
    int major;
    int minor;
    char devname[32];
    char partname[32];
    char dmname[32];
};

static vector<devinfo> dev_list;

static void parse_device(devinfo *dev, const char *uevent) {
    dev->partname[0] = '\0';
    parse_prop_file(uevent, [=](string_view key, string_view value) -> bool {
        if (key == "MAJOR")
            dev->major = parse_int(value.data());
        else if (key == "MINOR")
            dev->minor = parse_int(value.data());
        else if (key == "DEVNAME")
            strcpy(dev->devname, value.data());
        else if (key == "PARTNAME")
            strcpy(dev->partname, value.data());

        return true;
    });
}

static void collect_devices() {
    char path[128];
    devinfo dev{};
    if (auto dir = xopen_dir("/sys/dev/block"); dir) {
        for (dirent *entry; (entry = readdir(dir.get()));) {
            if (entry->d_name == "."sv || entry->d_name == ".."sv)
                continue;
            sprintf(path, "/sys/dev/block/%s/uevent", entry->d_name);
            parse_device(&dev, path);
            sprintf(path, "/sys/dev/block/%s/dm/name", entry->d_name);
            if (access(path, F_OK) == 0) {
                auto name = rtrim(full_read(path));
                strcpy(dev.dmname, name.data());
            }
            dev_list.push_back(dev);
        }
    }
}

static struct {
    char partname[32];
    char block_dev[64];
} blk_info;

static int64_t setup_block(bool write_block) {
    if (dev_list.empty())
        collect_devices();
    xmkdir("/dev", 0755);
    xmkdir("/dev/block", 0755);

    for (int tries = 0; tries < 3; ++tries) {
        for (auto &dev : dev_list) {
            if (strcasecmp(dev.partname, blk_info.partname) == 0)
                LOGD("Setup %s: [%s] (%d, %d)\n", dev.partname, dev.devname, dev.major, dev.minor);
            else if (strcasecmp(dev.dmname, blk_info.partname) == 0)
                LOGD("Setup %s: [%s] (%d, %d)\n", dev.dmname, dev.devname, dev.major, dev.minor);
            else
                continue;

            if (write_block) {
                sprintf(blk_info.block_dev, "/dev/block/%s", dev.devname);
            }
            dev_t rdev = makedev(dev.major, dev.minor);
            xmknod(blk_info.block_dev, S_IFBLK | 0600, rdev);
            return rdev;
        }
        // Wait 10ms and try again
        usleep(10000);
        dev_list.clear();
        collect_devices();
    }

    // The requested partname does not exist
    return -1;
}

static bool is_lnk(const char *name) {
    struct stat st;
    if (lstat(name, &st))
        return false;
    return S_ISLNK(st.st_mode);
}

#define read_info(val) \
if (access(#val, F_OK) == 0) {\
    entry.val = rtrim(full_read(#val)); \
}

void BaseInit::read_dt_fstab(vector<fstab_entry> &fstab) {
    if (access(cmd->dt_dir, F_OK) != 0)
        return;

    char cwd[128];
    getcwd(cwd, sizeof(cwd));
    chdir(cmd->dt_dir);
    run_finally cd([&]{ chdir(cwd); });

    if (access("fstab", F_OK) != 0)
        return;
    chdir("fstab");

    // Make sure dt fstab is enabled
    if (access("status", F_OK) == 0) {
        auto status = rtrim(full_read("status"));
        if (status != "okay" && status != "ok")
            return;
    }

    auto dir = xopen_dir(".");
    for (dirent *dp; (dp = xreaddir(dir.get()));) {
        if (dp->d_type != DT_DIR)
            continue;
        chdir(dp->d_name);
        run_finally f([]{ chdir(".."); });

        if (access("status", F_OK) == 0) {
            auto status = rtrim(full_read("status"));
            if (status != "okay" && status != "ok")
                continue;
        }

        fstab_entry entry;

        read_info(dev);
        read_info(mnt_point) else {
            entry.mnt_point = "/";
            entry.mnt_point += dp->d_name;
        }
        read_info(type);
        read_info(mnt_flags);
        read_info(fsmgr_flags);

        fstab.emplace_back(std::move(entry));
    }
}

void MagiskInit::mount_with_dt() {
    vector<fstab_entry> fstab;
    read_dt_fstab(fstab);
    for (const auto &entry : fstab) {
        if (is_lnk(entry.mnt_point.data()))
            continue;
        // Derive partname from dev
        sprintf(blk_info.partname, "%s%s", basename(entry.dev.data()), cmd->slot);
        setup_block(true);
        xmkdir(entry.mnt_point.data(), 0755);
        xmount(blk_info.block_dev, entry.mnt_point.data(), entry.type.data(), MS_RDONLY, nullptr);
        mount_list.push_back(entry.mnt_point);
    }
}

static void switch_root(const string &path) {
    LOGD("Switch root to %s\n", path.data());
    int root = xopen("/", O_RDONLY);
    vector<string> mounts;
    parse_mnt("/proc/mounts", [&](mntent *me) {
        // Skip root and self
        if (me->mnt_dir == "/"sv || me->mnt_dir == path)
            return true;
        // Do not include subtrees
        for (const auto &m : mounts) {
            if (strncmp(me->mnt_dir, m.data(), m.length()) == 0 && me->mnt_dir[m.length()] == '/')
                return true;
        }
        mounts.emplace_back(me->mnt_dir);
        return true;
    });
    for (auto &dir : mounts) {
        auto new_path = path + dir;
        mkdir(new_path.data(), 0755);
        xmount(dir.data(), new_path.data(), nullptr, MS_MOVE, nullptr);
    }
    chdir(path.data());
    xmount(path.data(), "/", nullptr, MS_MOVE, nullptr);
    chroot(".");

    LOGD("Cleaning rootfs\n");
    frm_rf(root);
}

void MagiskInit::mount_rules_dir(const char *dev_base, const char *mnt_base) {
    char path[128];
    xrealpath(dev_base, blk_info.block_dev);
    xrealpath(mnt_base, path);
    char *b = blk_info.block_dev + strlen(blk_info.block_dev);
    char *p = path + strlen(path);

    auto do_mount = [&](const char *type) -> bool {
        xmkdir(path, 0755);
        bool success = xmount(blk_info.block_dev, path, type, 0, nullptr) == 0;
        if (success)
            mount_list.emplace_back(path);
        return success;
    };

    // First try userdata
    strcpy(blk_info.partname, "userdata");
    strcpy(b, "/data");
    strcpy(p, "/data");
    if (setup_block(false) < 0) {
        // Try NVIDIA naming scheme
        strcpy(blk_info.partname, "UDA");
        if (setup_block(false) < 0)
            goto cache;
    }
    // WARNING: DO NOT ATTEMPT TO MOUNT F2FS AS IT MAY CRASH THE KERNEL
    // Failure means either f2fs, FDE, or metadata encryption
    if (!do_mount("ext4"))
        goto cache;

    strcpy(p, "/data/unencrypted");
    if (xaccess(path, F_OK) == 0) {
        // FBE, need to use an unencrypted path
        custom_rules_dir = path + "/magisk"s;
    } else {
        // Skip if /data/adb does not exist
        strcpy(p, SECURE_DIR);
        if (xaccess(path, F_OK) != 0)
            return;
        strcpy(p, MODULEROOT);
        if (xaccess(path, F_OK) != 0) {
            goto cache;
        }
        // Unencrypted, directly use module paths
        custom_rules_dir = string(path);
    }
    goto success;

cache:
    // Fallback to cache
    strcpy(blk_info.partname, "cache");
    strcpy(b, "/cache");
    strcpy(p, "/cache");
    if (setup_block(false) < 0) {
        // Try NVIDIA naming scheme
        strcpy(blk_info.partname, "CAC");
        if (setup_block(false) < 0)
            goto metadata;
    }
    if (!do_mount("ext4"))
        goto persist;
    custom_rules_dir = path + "/magisk"s;
    goto success;

persist:
    // Fallback to persist
    strcpy(blk_info.partname, "persist");
    strcpy(b, "/persist");
    strcpy(p, "/persist");
    if (setup_block(false) < 0 || !do_mount("ext4"))
        goto metadata;
    custom_rules_dir = path + "/magisk"s;
    goto success;

metadata:
    // Fallback to metadata
    strcpy(blk_info.partname, "metadata");
    strcpy(b, "/metadata");
    strcpy(p, "/metadata");
    if (setup_block(false) < 0 || !do_mount("ext4"))
        return;
    custom_rules_dir = path + "/magisk"s;

success:
    // Create symlinks so we don't need to go through this logic again
    strcpy(p, "/sepolicy.rules");
    xsymlink(custom_rules_dir.data(), path);
}

void RootFSInit::early_mount() {
    self = mmap_data::ro("/init");

    LOGD("Restoring /init\n");
    rename("/.backup/init", "/init");

    mount_with_dt();
}

void SARBase::backup_files() {
    if (access("/overlay.d", F_OK) == 0)
        backup_folder("/overlay.d", overlays);

    self = mmap_data::ro("/proc/self/exe");
    if (access("/.backup/.magisk", R_OK) == 0)
        config = mmap_data::ro("/.backup/.magisk");
}

void SARBase::mount_system_root() {
    LOGD("Early mount system_root\n");
    strcpy(blk_info.block_dev, "/dev/root");

    do {
        // Try legacy SAR dm-verity
        strcpy(blk_info.partname, "vroot");
        auto dev = setup_block(false);
        if (dev >= 0)
            goto mount_root;

        // Try NVIDIA naming scheme
        strcpy(blk_info.partname, "APP");
        dev = setup_block(false);
        if (dev >= 0)
            goto mount_root;

        sprintf(blk_info.partname, "system%s", cmd->slot);
        dev = setup_block(false);
        if (dev >= 0)
            goto mount_root;

        // Poll forever if rootwait was given in cmdline
    } while (cmd->rootwait);

    // We don't really know what to do at this point...
    LOGE("Cannot find root partition, abort\n");
    exit(1);
mount_root:
    xmkdir("/system_root", 0755);
    if (xmount("/dev/root", "/system_root", "ext4", MS_RDONLY, nullptr))
        xmount("/dev/root", "/system_root", "erofs", MS_RDONLY, nullptr);
}

void SARInit::early_mount() {
    backup_files();
    mount_system_root();
    switch_root("/system_root");

    // Use the apex folder to determine whether 2SI (Android 10+)
    is_two_stage = access("/apex", F_OK) == 0;
    LOGD("is_two_stage: [%d]\n", is_two_stage);

    if (!is_two_stage) {
        // Make dev writable
        xmkdir("/dev", 0755);
        xmount("tmpfs", "/dev", "tmpfs", 0, "mode=755");
        mount_list.emplace_back("/dev");
        mount_with_dt();
    }
}

void SecondStageInit::prepare() {
    backup_files();

    umount2("/init", MNT_DETACH);
    umount2("/proc/self/exe", MNT_DETACH);

    if (access("/system_root", F_OK) == 0)
        switch_root("/system_root");
}

void BaseInit::exec_init() {
    // Unmount in reverse order
    for (auto &p : reversed(mount_list)) {
        if (xumount(p.data()) == 0)
            LOGD("Unmount [%s]\n", p.data());
    }
    execv("/init", argv);
    exit(1);
}

void MagiskInit::setup_tmp(const char *path) {
    LOGD("Setup Magisk tmp at %s\n", path);
    xmount("tmpfs", path, "tmpfs", 0, "mode=755");

    chdir(path);

    xmkdir(INTLROOT, 0755);
    xmkdir(MIRRDIR, 0);
    xmkdir(BLOCKDIR, 0);

    int fd = xopen(INTLROOT "/config", O_WRONLY | O_CREAT, 0);
    xwrite(fd, config.buf, config.sz);
    close(fd);
    fd = xopen("magiskinit", O_WRONLY | O_CREAT, 0755);
    xwrite(fd, self.buf, self.sz);
    close(fd);

    // The magisk binary will be handled later

    // Create applet symlinks
    for (int i = 0; applet_names[i]; ++i)
        xsymlink("./magisk", applet_names[i]);
    xsymlink("./magiskinit", "magiskpolicy");
    xsymlink("./magiskinit", "supolicy");

    chdir("/");
}
