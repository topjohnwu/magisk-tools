#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <linux/input.h>
#include <fcntl.h>
#include <vector>

#include <utils.hpp>

#include "init.hpp"

using namespace std;

vector<string> mount_list;

template<char... cs> using chars = integer_sequence<char, cs...>;

template<char... escapes, char... breaks>
static string extract_qutoed_str_until(chars<escapes...>, chars<breaks...>,
                                       string_view sv, size_t &begin, bool &quoted) {
    string result;
    char match_array[] = {escapes..., breaks..., '"'};
    string_view match(match_array, sizeof(match_array));
    for (auto end = begin;; ++end) {
        end = sv.find_first_of(match, end);
        if (end == string_view::npos || ((sv[end] == breaks) || ...) ||
            (!quoted && ((sv[end] == escapes) || ...))) {
            result.append(sv.substr(begin, end - begin));
            begin = end;
            return result;
        }
        if (sv[end] == '"') {
            quoted = !quoted;
            result.append(sv.substr(begin, end - begin));
            begin = end + 1;
        }
    }
}

//[pair_split][key][assign_split][assign][assign_split][value][pair_split]
template<char... pair_spilt, char... assign, char... assign_split>
static auto parse(chars<pair_spilt...>, chars<assign...>, chars<assign_split...>, string_view sv) {
    vector<pair<string, string>> kv;
    char next_array[] = {pair_spilt...};
    string_view next(next_array, sizeof(next_array));
    char skip_array[] = {assign..., assign_split...};
    string_view skip(skip_array, sizeof(skip_array));
    bool quoted = false;
    for (size_t cur = 0u; cur < sv.size();
         cur = sv.find_first_not_of(next, cur)) {
        auto key = extract_qutoed_str_until(chars<assign_split..., pair_spilt...>{},
                                            chars<assign...>{}, sv, cur, quoted);
        cur = sv.find_first_not_of(skip, cur);
        if (((cur == string_view::npos) || ... || (sv[cur] == pair_spilt))) {
            kv.emplace_back(key, "");
            continue;
        }
        auto value = extract_qutoed_str_until(chars<pair_spilt...>{}, chars<>{}, sv, cur, quoted);
        kv.emplace_back(key, value);
    }
    return kv;
}

static auto parse_cmdline(string_view sv) {
    return parse(chars<' '>{}, chars<'='>{}, chars<>{}, sv);
}
static auto parse_bootconfig(string_view sv) {
    return parse(chars<'\n'>{}, chars<'='>{}, chars<' '>{}, sv);
}

#define test_bit(bit, array) (array[bit / 8] & (1 << (bit % 8)))

static bool check_key_combo() {
    uint8_t bitmask[(KEY_MAX + 1) / 8];
    vector<int> events;
    constexpr const char *name = "/event";

    for (int minor = 64; minor < 96; ++minor) {
        if (xmknod(name, S_IFCHR | 0444, makedev(13, minor)))
            continue;
        int fd = open(name, O_RDONLY | O_CLOEXEC);
        unlink(name);
        if (fd < 0)
            continue;
        memset(bitmask, 0, sizeof(bitmask));
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(bitmask)), bitmask);
        if (test_bit(KEY_VOLUMEUP, bitmask))
            events.push_back(fd);
        else
            close(fd);
    }
    if (events.empty())
        return false;

    run_finally fin([&] { for_each(events.begin(), events.end(), close); });

    // Return true if volume up key is held for more than 3 seconds
    int count = 0;
    for (int i = 0; i < 500; ++i) {
        for (const int &fd : events) {
            memset(bitmask, 0, sizeof(bitmask));
            ioctl(fd, EVIOCGKEY(sizeof(bitmask)), bitmask);
            if (test_bit(KEY_VOLUMEUP, bitmask)) {
                count++;
                break;
            }
        }
        if (count >= 300) {
            LOGD("KEY_VOLUMEUP detected: disable system-as-root\n");
            return true;
        }
        // Check every 10ms
        usleep(10000);
    }
    return false;
}

static FILE *kmsg;
static char kmsg_buf[4096];
static int vprintk(const char *fmt, va_list ap) {
    vsnprintf(kmsg_buf + 12, sizeof(kmsg_buf) - 12, fmt, ap);
    return fprintf(kmsg, "%s", kmsg_buf);
}
void setup_klog() {
    // Shut down first 3 fds
    int fd;
    if (access("/dev/null", W_OK) == 0) {
        fd = xopen("/dev/null", O_RDWR | O_CLOEXEC);
    } else {
        mknod("/null", S_IFCHR | 0666, makedev(1, 3));
        fd = xopen("/null", O_RDWR | O_CLOEXEC);
        unlink("/null");
    }
    xdup3(fd, STDIN_FILENO, O_CLOEXEC);
    xdup3(fd, STDOUT_FILENO, O_CLOEXEC);
    xdup3(fd, STDERR_FILENO, O_CLOEXEC);
    if (fd > STDERR_FILENO)
        close(fd);

    if (access("/dev/kmsg", W_OK) == 0) {
        fd = xopen("/dev/kmsg", O_WRONLY | O_CLOEXEC);
    } else {
        mknod("/kmsg", S_IFCHR | 0666, makedev(1, 11));
        fd = xopen("/kmsg", O_WRONLY | O_CLOEXEC);
        unlink("/kmsg");
    }

    kmsg = fdopen(fd, "w");
    setbuf(kmsg, nullptr);
    log_cb.d = log_cb.i = log_cb.w = log_cb.e = vprintk;
    log_cb.ex = nop_ex;
    strcpy(kmsg_buf, "magiskinit: ");

    // Disable kmsg rate limiting
    if (FILE *rate = fopen("/proc/sys/kernel/printk_devkmsg", "w")) {
        fprintf(rate, "on\n");
        fclose(rate);
    }
}

#define read_dt(name, key) \
sprintf(file_name, "%s/" name, cmd->dt_dir); \
if (access(file_name, R_OK) == 0){ \
    string data = full_read(file_name); \
    if (!data.empty()) { \
        data.pop_back(); \
        strcpy(cmd->key, data.data()); \
    } \
}

void load_kernel_info(cmdline *cmd) {
    // Get kernel data using procfs and sysfs
    xmkdir("/proc", 0755);
    xmount("proc", "/proc", "proc", 0, nullptr);
    xmkdir("/sys", 0755);
    xmount("sysfs", "/sys", "sysfs", 0, nullptr);

    mount_list.emplace_back("/proc");
    mount_list.emplace_back("/sys");

    // Log to kernel
    setup_klog();

    for (const auto&[key, value] : parse_cmdline(full_read("/proc/cmdline"))) {
        if (key == "androidboot.slot_suffix") {
            strcpy(cmd->slot, value.data());
        } else if (key == "androidboot.slot") {
            cmd->slot[0] = '_';
            strcpy(cmd->slot + 1, value.data());
        } else if (key == "skip_initramfs") {
            cmd->skip_initramfs = true;
        } else if (key == "androidboot.force_normal_boot") {
            cmd->force_normal_boot = value[0] == '1';
        } else if (key == "rootwait") {
            cmd->rootwait = true;
        } else if (key == "androidboot.android_dt_dir") {
            strcpy(cmd->dt_dir, value.data());
        } else if (key == "androidboot.hardware") {
            strcpy(cmd->hardware, value.data());
        } else if (key == "androidboot.hardware.platform") {
            strcpy(cmd->hardware_plat, value.data());
        } else if (key == "androidboot.fstab_suffix") {
            strcpy(cmd->fstab_suffix, value.data());
        }
    }

    LOGD("Kernel cmdline info:\n");
    LOGD("skip_initramfs=[%d]\n", cmd->skip_initramfs);
    LOGD("force_normal_boot=[%d]\n", cmd->force_normal_boot);
    LOGD("rootwait=[%d]\n", cmd->rootwait);
    LOGD("slot=[%s]\n", cmd->slot);
    LOGD("dt_dir=[%s]\n", cmd->dt_dir);
    LOGD("fstab_suffix=[%s]\n", cmd->fstab_suffix);
    LOGD("hardware=[%s]\n", cmd->hardware);
    LOGD("hardware.platform=[%s]\n", cmd->hardware_plat);

    for (const auto&[key, value] : parse_bootconfig(full_read("/proc/bootconfig"))) {
        if (key == "androidboot.slot_suffix") {
            strcpy(cmd->slot, value.data());
        } else if (key == "androidboot.force_normal_boot") {
            cmd->force_normal_boot = value[0] == '1';
        } else if (key == "androidboot.android_dt_dir") {
            strcpy(cmd->dt_dir, value.data());
        } else if (key == "hardware") {
            strcpy(cmd->hardware, value.data());
        } else if (key == "androidboot.hardware.platform") {
            strcpy(cmd->hardware_plat, value.data());
        } else if (key == "androidboot.fstab_suffix") {
            strcpy(cmd->fstab_suffix, value.data());
        }
    }

    LOGD("Boot config info:\n");
    LOGD("force_normal_boot=[%d]\n", cmd->force_normal_boot);
    LOGD("slot=[%s]\n", cmd->slot);
    LOGD("dt_dir=[%s]\n", cmd->dt_dir);
    LOGD("fstab_suffix=[%s]\n", cmd->fstab_suffix);
    LOGD("hardware=[%s]\n", cmd->hardware);
    LOGD("hardware.platform=[%s]\n", cmd->hardware_plat);

    parse_prop_file("/.backup/.magisk", [=](auto key, auto value) -> bool {
        if (key == "RECOVERYMODE" && value == "true") {
            LOGD("Running in recovery mode, waiting for key...\n");
            cmd->skip_initramfs = !check_key_combo();
            return false;
        }
        return true;
    });

    if (cmd->dt_dir[0] == '\0')
        strcpy(cmd->dt_dir, DEFAULT_DT_DIR);

    char file_name[128];
    read_dt("fstab_suffix", fstab_suffix)
    read_dt("hardware", hardware)
    read_dt("hardware.platform", hardware_plat)

    LOGD("Device tree info:\n");
    LOGD("dt_dir=[%s]\n", cmd->dt_dir);
    LOGD("fstab_suffix=[%s]\n", cmd->fstab_suffix);
    LOGD("hardware=[%s]\n", cmd->hardware);
    LOGD("hardware.platform=[%s]\n", cmd->hardware_plat);
}

bool check_two_stage() {
    if (access("/apex", F_OK) == 0)
        return true;
    if (access("/system/bin/init", F_OK) == 0)
        return true;
    // If we still have no indication, parse the original init and see what's up
    auto init = mmap_data::ro("/.backup/init");
    return init.contains("selinux_setup");
}
