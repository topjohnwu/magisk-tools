# Internal Details

## File Structure

### Paths in "Magisk tmpfs directory"

Magisk will mount a `tmpfs` directory to store some temporary data. For devices with the `/sbin` folder, it will be chosen as it will also act as an overlay to inject binaries into `PATH`. From Android 11 onwards, the `/sbin` folder might not exist, so Magisk will randomly create a folder under `/dev` and use it as the base folder.

```
# In order to get the current base folder Magisk is using,
# use the command `magisk --path`.
# Binaries like magisk, magiskinit, and all symlinks to
# applets are directly stored in this path. This means when
# this is /sbin, these binaries will be directly in PATH.
MAGISKPATH=$(magisk --path)

# Magisk internal stuffs
MAGISKTMP=$MAGISKBASE/.magisk

# Magisk's BusyBox directory. Within this folder stores
# the busybox binary and symlinks to all of its applets.
# Any usage of this directory is deprecated, please
# directly call /data/adb/magisk/busybox and use
# BusyBox's ASH Standalone mode.
# The creation of this path will be removed in the future.
$MAGISKTMP/busybox

# /data/adb/modules will be bind mounted here.
# The original folder is not used due to nosuid mount flag.
$MAGISKTMP/modules

# The current Magisk installation config
$MAGISKTMP/config

# Partition mirrors
# Each directory in this path will be mounted with the
# partition of its directory name.
# e.g. system, system_ext, vendor, data ...
$MAGISKTMP/mirror

# Block devices Magisk creates internally to mount mirrors.
$MAGISKTMP/block

# Root directory patch files
# On system-as-root devices, / is not writable.
# All pre-init patched files are stored here and bind mounted.
$MAGISKTMP/rootdir
```

### Paths in `/data`

Some binaries and files should be stored on non-volatile storages in `/data`. In order to prevent detection, everything has to be stored somewhere safe and undetectable in `/data`. The folder `/data/adb` was chosen because of the following advantages:

- It is an existing folder on modern Android, so it cannot be used as an indication of the existence of Magisk.
- The permission of the folder is by default `700`, owner as `root`, so non-root processes are unable to enter, read, write the folder in any possible way.
- The folder is labeled with secontext `u:object_r:adb_data_file:s0`, and very few processes have the permission to do any interaction with that secontext.

```
SECURE_DIR=/data/adb

# Folder storing general post-fs-data scripts
$SECURE_DIR/post-fs-data.d

# Folder storing general late_start service scripts
$SECURE_DIR/service.d

# Magisk modules
$SECURE_DIR/modules

# Magisk modules that are pending for upgrade
# Module files are not safe to be modified when mounted
# Modules installed through the Magisk app will be stored here
# and will be merged into $SECURE_DIR/modules in the next reboot
$SECURE_DIR/modules_update

# Database storing settings and root permissions
MAGISKDB=$SECURE_DIR/magisk.db

# All magisk related binaries, including busybox,
# scripts, and magisk binaries. Used in supporting
# module installation, addon.d, the Magisk app etc.
DATABIN=$SECURE_DIR/magisk

```

## Magisk Booting Process

### Pre-Init

`magiskinit` will replace `init` as the first program to run.

- Early mount required partitions. On legacy system-as-root devices, we switch root to system; on 2SI devices, we patch fstab and execute the original `init` to mount partitions for us.
- Load sepolicy either from `/sepolicy`, precompiled sepolicy in vendor, or compile split sepolicy
- Patch sepolicy rules and dump to `/sepolicy` or `/sbin/.se` or `/dev/.se`
- Patch `init` or `libselinux.so` to force the system to load the patched policies
- Inject magisk services into `init.rc`
- Execute the original `init` to continue the boot process

### post-fs-data

This triggers on `post-fs-data` when `/data` is decrypted and mounted. The daemon `magiskd` will be launched, post-fs-data scripts are executed, and module files are magic mounted.

### late_start

Later in the booting process, the class `late_start` will be triggered, and Magisk "service" mode will be started. In this mode, service scripts are executed.

## Resetprop

Usually, system properties are designed to only be updated by `init` and read-only to non-root processes. With root you can change properties by sending requests to `property_service` (hosted by `init`) using commands such as `setprop`, but changing read-only props (props that start with `ro.` like `ro.build.product`) and deleting properties are still prohibited.

`resetprop` is implemented by distilling out the source code related to system properties from AOSP and patched to allow direct modification to property area, or `prop_area`, bypassing the need to go through `property_service`. Since we are bypassing `property_service`, there are a few caveats:

- `on property:foo=bar` actions registered in `*.rc` scripts will not be triggered if property changes does not go through `property_service`. The default set property behavior of `resetprop` matches `setprop`, which **WILL** trigger events (implemented by first deleting the property then set it via `property_service`). There is a flag `-n` to disable it if you need this special behavior.
- persist properties (props that starts with `persist.`, like `persist.sys.usb.config`) are stored in both `prop_area` and `/data/property`. By default, deleting props will **NOT** remove it from persistent storage, meaning the property will be restored after the next reboot; reading props will **NOT** read from persistent storage, as this is the behavior of `getprop`. With the flag `-p`, deleting props will remove the prop in **BOTH** `prop_area` and `/data/property`, and reading props will be read from **BOTH** `prop_area` and persistent storage.

## Magic Mount

The details of the actual implementation and algorithm of Magic Mount is omitted here, please directly dive into the source code if interested (`core/module.cpp`).

Even though the mounting logic is very complicated, the final result of Magic Mount is actually pretty simple. For each module, the folder `$MODPATH/system` will be recursively merged into the real `/system`; that is: existing files in the real system will be replaced by the one in modules' system, and new files in modules' system will be added to the real system.

There is one additional trick you can use: if you place an empty file named `.replace` in any of the folders in a module's system, instead of merging the contents, that folder will directly replace the one in the real system. This will be very handy in some cases, for example swapping out a system app.

If you want to replace files in `/vendor` or `/product`, please place them under `$MODPATH/system/vendor` or `$MODPATH/system/product`. Magisk will transparently handle both cases, whether vendor or product is a separate partition or not.

## SELinux Policies

Magisk will patch the stock `sepolicy` to make sure root and Magisk operations can be done in a safe and secure way. The new domain `magisk` is effectively permissive, which is what `magiskd` and all root shell will run in. `magisk_file` is a new file type that is setup to be allowed to be accessed by every domain (unrestricted file context).

Before Android 8.0, all allowed su client domains are allowed to directly connect to `magiskd` and establish connection with the daemon to get a remote root shell. Magisk also have to relax some `ioctl` operations so root shells can function properly.

After Android 8.0, to reduce relaxation of rules in Android's sandbox, a new SELinux model is deployed. The `magisk` binary is labelled with `magisk_exec` file type, and processes running as allowed su client domains executing the `magisk` binary (this includes the `su` command) will transit to `magisk_client` by using a `type_transition` rule. Rules strictly restrict that only `magisk` domain processes are allowed to attribute files to `magisk_exec`. Direct connection to sockets of `magiskd` are not allowed; the only way to access the daemon is through a `magisk_client` process. These changes allow us to keep the sandbox intact, and keep Magisk specific rules separated from the rest of the policies.

The full set of rules can be found in `magiskpolicy/rules.cpp`.
