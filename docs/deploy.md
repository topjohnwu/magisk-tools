# Deployment
(Note: This is not a user tutorial for installing Magisk, this is an explanation of how Magisk can be installed, and a guide for developers to properly deploy Magisk in various different situations)

## Systemless
When a user flashes a Magisk zip in custom recoveries or have boot images patched in the Magisk app, Magisk is installed in the systemless fashion. This is the only officially supported method to install Magisk on a device. The systemless method installs Magisk into a boot image's ramdisk CPIO, sometimes require additional patches to the kernel.

Here are the bare minimum commands to install Magisk into a stock boot/recovery image. Be aware that the actual Magisk installation is a little more complicated, the following commands will work but should be treat as proof-of-concepts.

```
# Push 2 binaries, magiskboot and magiskinit to the device

# Assume the 2 binaries are in the current directory and
# both have executing permissions.

# The path to stock boot image, can be a file or an on-device block
BOOTIMAGE=<path to boot>

# First unpack the image
./magiskboot unpack $BOOTIMAGE

# In normal cases, after unpacking you should get at least kernel and ramdisk.cpio

# Patch ramdisk
./magiskboot cpio ramdisk.cpio \
"mkdir 000 .backup" \          # create a folder to store our init backup
"mv init .backup/init" \       # backup the original init
"add 750 init magiskinit"      # replace init with magiskinit

# Patch kernel to always use ramdisk as rootfs
# You only need to do this on system-as-root devices
./magiskboot hexpatch kernel \
736B69705F696E697472616D6673 \
77616E745F696E697472616D6673

# Repack the boot image
./magiskboot repack $BOOTIMAGE

# The patched image should be located in new-boot.img
```

## System Only
WIP

```
# Currently not available
```

## Emulators (Official AVB Only)
The script `scripts/emulator.sh` allows you to establish a minimal Magisk environment within the official Android Virtual Device included along with Android Studio / SDK. Please check the comments in the script for further information.

## Exploits
**(Note: Magisk could only be used as root)**

Occasionally, there would be exploits in certain devices that could lead to full fledged root. On modern Android, it is possible to use MagiskSU if you can gain a shell with the following conditions:

- Effective UID should be privileged (root, or `euid=0`)
- Full Linux capabilities
- Have the ability to reload `sepolicy` (which 99.9% of the time means SELinux permissive)

You can check out `scripts/emulator.sh` as a reference for bringing up Magisk with a root shell. Note that these changes are not persistent, and you will need to find ways to rerun the whole process every boot.
