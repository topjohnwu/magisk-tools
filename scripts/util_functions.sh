############################################
# Magisk General Utility Functions
############################################

#MAGISK_VERSION_STUB

###################
# Helper Functions
###################

ui_print() {
  if $BOOTMODE; then
    echo "$1"
  else
    echo -e "ui_print $1\nui_print" >> /proc/self/fd/$OUTFD
  fi
}

toupper() {
  echo "$@" | tr '[:lower:]' '[:upper:]'
}

grep_cmdline() {
  local REGEX="s/^$1=//p"
  cat /proc/cmdline | tr '[:space:]' '\n' | sed -n "$REGEX" 2>/dev/null
}

grep_prop() {
  local REGEX="s/^$1=//p"
  shift
  local FILES=$@
  [ -z "$FILES" ] && FILES='/system/build.prop'
  cat $FILES 2>/dev/null | dos2unix | sed -n "$REGEX" | head -n 1
}

grep_get_prop() {
  local result=$(grep_prop $@)
  if [ -z "$result" ]; then
    # Fallback to getprop
    getprop "$1"
  else
    echo $result
  fi
}

getvar() {
  local VARNAME=$1
  local VALUE
  local PROPPATH='/data/.magisk /cache/.magisk'
  [ ! -z $MAGISKTMP ] && PROPPATH="$MAGISKTMP/config $PROPPATH"
  VALUE=$(grep_prop $VARNAME $PROPPATH)
  [ ! -z $VALUE ] && eval $VARNAME=\$VALUE
}

is_mounted() {
  grep -q " $(readlink -f $1) " /proc/mounts 2>/dev/null
  return $?
}

abort() {
  ui_print "$1"
  $BOOTMODE || recovery_cleanup
  [ ! -z $MODPATH ] && rm -rf $MODPATH
  rm -rf $TMPDIR
  exit 1
}

resolve_vars() {
  MAGISKBIN=$NVBASE/magisk
  POSTFSDATAD=$NVBASE/post-fs-data.d
  SERVICED=$NVBASE/service.d
}

print_title() {
  local len line1len line2len pounds
  line1len=$(echo -n $1 | wc -c)
  line2len=$(echo -n $2 | wc -c)
  len=$line2len
  [ $line1len -gt $line2len ] && len=$line1len
  len=$((len + 2))
  pounds=$(printf "%${len}s" | tr ' ' '*')
  ui_print "$pounds"
  ui_print " $1 "
  [ "$2" ] && ui_print " $2 "
  ui_print "$pounds"
}

######################
# Environment Related
######################

setup_flashable() {
  ensure_bb
  $BOOTMODE && return
  if [ -z $OUTFD ] || readlink /proc/$$/fd/$OUTFD | grep -q /tmp; then
    # We will have to manually find out OUTFD
    for FD in `ls /proc/$$/fd`; do
      if readlink /proc/$$/fd/$FD | grep -q pipe; then
        if ps | grep -v grep | grep -qE " 3 $FD |status_fd=$FD"; then
          OUTFD=$FD
          break
        fi
      fi
    done
  fi
  recovery_actions
}

ensure_bb() {
  if set -o | grep -q standalone; then
    # We are definitely in busybox ash
    set -o standalone
    return
  fi

  # Find our busybox binary
  local bb
  if [ -f $TMPDIR/busybox ]; then
    bb=$TMPDIR/busybox
  elif [ -f $MAGISKBIN/busybox ]; then
    bb=$MAGISKBIN/busybox
  else
    abort "! Cannot find BusyBox"
  fi
  chmod 755 $bb

  # Busybox could be a script, make sure /system/bin/sh exists
  if [ ! -f /system/bin/sh ]; then
    umount -l /system 2>/dev/null
    mkdir -p /system/bin
    ln -s $(command -v sh) /system/bin/sh
  fi

  export ASH_STANDALONE=1

  # Find our current arguments
  # Run in busybox environment to ensure consistent results
  # /proc/<pid>/cmdline shall be <interpreter> <script> <arguments...>
  local cmds="$($bb sh -c "
  for arg in \$(tr '\0' '\n' < /proc/$$/cmdline); do
    if [ -z \"\$cmds\" ]; then
      # Skip the first argument as we want to change the interpreter
      cmds=\"sh\"
    else
      cmds=\"\$cmds '\$arg'\"
    fi
  done
  echo \$cmds")"

  # Re-exec our script
  echo $cmds | $bb xargs $bb
  exit
}

recovery_actions() {
  # Make sure random won't get blocked
  mount -o bind /dev/urandom /dev/random
  # Unset library paths
  OLD_LD_LIB=$LD_LIBRARY_PATH
  OLD_LD_PRE=$LD_PRELOAD
  OLD_LD_CFG=$LD_CONFIG_FILE
  unset LD_LIBRARY_PATH
  unset LD_PRELOAD
  unset LD_CONFIG_FILE
}

recovery_cleanup() {
  local DIR
  ui_print "- Unmounting partitions"
  (umount_apex
  if [ ! -d /postinstall/tmp ]; then
    umount -l /system
    umount -l /system_root
  fi
  umount -l /vendor
  umount -l /persist
  umount -l /metadata
  for DIR in /apex /system /system_root; do
    if [ -L "${DIR}_link" ]; then
      rmdir $DIR
      mv -f ${DIR}_link $DIR
    fi
  done
  umount -l /dev/random) 2>/dev/null
  [ -z $OLD_LD_LIB ] || export LD_LIBRARY_PATH=$OLD_LD_LIB
  [ -z $OLD_LD_PRE ] || export LD_PRELOAD=$OLD_LD_PRE
  [ -z $OLD_LD_CFG ] || export LD_CONFIG_FILE=$OLD_LD_CFG
}

#######################
# Installation Related
#######################

# find_block [partname...]
find_block() {
  local BLOCK DEV DEVICE DEVNAME PARTNAME UEVENT
  for BLOCK in "$@"; do
    DEVICE=`find /dev/block \( -type b -o -type c -o -type l \) -iname $BLOCK | head -n 1` 2>/dev/null
    if [ ! -z $DEVICE ]; then
      readlink -f $DEVICE
      return 0
    fi
  done
  # Fallback by parsing sysfs uevents
  for UEVENT in /sys/dev/block/*/uevent; do
    DEVNAME=`grep_prop DEVNAME $UEVENT`
    PARTNAME=`grep_prop PARTNAME $UEVENT`
    for BLOCK in "$@"; do
      if [ "$(toupper $BLOCK)" = "$(toupper $PARTNAME)" ]; then
        echo /dev/block/$DEVNAME
        return 0
      fi
    done
  done
  # Look just in /dev in case we're dealing with MTD/NAND without /dev/block devices/links
  for DEV in "$@"; do
    DEVICE=`find /dev \( -type b -o -type c -o -type l \) -maxdepth 1 -iname $DEV | head -n 1` 2>/dev/null
    if [ ! -z $DEVICE ]; then
      readlink -f $DEVICE
      return 0
    fi
  done
  return 1
}

# setup_mntpoint <mountpoint>
setup_mntpoint() {
  local POINT=$1
  [ -L $POINT ] && mv -f $POINT ${POINT}_link
  if [ ! -d $POINT ]; then
    rm -f $POINT
    mkdir -p $POINT
  fi
}

# mount_name <partname(s)> <mountpoint> <flag>
mount_name() {
  local PART=$1
  local POINT=$2
  local FLAG=$3
  setup_mntpoint $POINT
  is_mounted $POINT && return
  # First try mounting with fstab
  mount $FLAG $POINT 2>/dev/null
  if ! is_mounted $POINT; then
    local BLOCK=$(find_block $PART)
    mount $FLAG $BLOCK $POINT || return
  fi
  ui_print "- Mounting $POINT"
}

# mount_ro_ensure <partname(s)> <mountpoint>
mount_ro_ensure() {
  # We handle ro partitions only in recovery
  $BOOTMODE && return
  local PART=$1
  local POINT=$2
  mount_name "$PART" $POINT '-o ro'
  is_mounted $POINT || abort "! Cannot mount $POINT"
}

mount_partitions() {
  # Check A/B slot
  SLOT=`grep_cmdline androidboot.slot_suffix`
  if [ -z $SLOT ]; then
    SLOT=`grep_cmdline androidboot.slot`
    [ -z $SLOT ] || SLOT=_${SLOT}
  fi
  [ -z $SLOT ] || ui_print "- Current boot slot: $SLOT"

  # Mount ro partitions
  if is_mounted /system_root; then
    umount /system 2&>/dev/null
    umount /system_root 2&>/dev/null
  fi
  mount_ro_ensure "system$SLOT app$SLOT" /system
  if [ -f /system/init -o -L /system/init ]; then
    SYSTEM_ROOT=true
    setup_mntpoint /system_root
    if ! mount --move /system /system_root; then
      umount /system
      umount -l /system 2>/dev/null
      mount_ro_ensure "system$SLOT app$SLOT" /system_root
    fi
    mount -o bind /system_root/system /system
  else
    SYSTEM_ROOT=false
    grep ' / ' /proc/mounts | grep -qv 'rootfs' || grep -q ' /system_root ' /proc/mounts && SYSTEM_ROOT=true
  fi
  # /vendor is used only on some older devices for recovery AVBv1 signing so is not critical if fails
  [ -L /system/vendor ] && mount_name vendor$SLOT /vendor '-o ro'
  $SYSTEM_ROOT && ui_print "- Device is system-as-root"

  # Allow /system/bin commands (dalvikvm) on Android 10+ in recovery
  $BOOTMODE || mount_apex
}

# loop_setup <ext4_img>, sets LOOPDEV
loop_setup() {
  unset LOOPDEV
  local LOOP
  local MINORX=1
  [ -e /dev/block/loop1 ] && MINORX=$(stat -Lc '%T' /dev/block/loop1)
  local NUM=0
  while [ $NUM -lt 64 ]; do
    LOOP=/dev/block/loop$NUM
    [ -e $LOOP ] || mknod $LOOP b 7 $((NUM * MINORX))
    if losetup $LOOP "$1" 2>/dev/null; then
      LOOPDEV=$LOOP
      break
    fi
    NUM=$((NUM + 1))
  done
}

mount_apex() {
  $BOOTMODE || [ ! -d /system/apex ] && return
  local APEX DEST
  setup_mntpoint /apex
  mount -t tmpfs tmpfs /apex -o mode=755
  local PATTERN='s/.*"name":[^"]*"\([^"]*\).*/\1/p'
  for APEX in /system/apex/*; do
    if [ -f $APEX ]; then
      # APEX APKs, extract and loop mount
      unzip -qo $APEX apex_payload.img -d /apex
      DEST=$(unzip -qp $APEX apex_manifest.pb | strings | head -n 1)
      [ -z $DEST ] && DEST=$(unzip -qp $APEX apex_manifest.json | sed -n $PATTERN)
      [ -z $DEST ] && continue
      DEST=/apex/$DEST
      mkdir -p $DEST
      loop_setup /apex/apex_payload.img
      if [ ! -z $LOOPDEV ]; then
        ui_print "- Mounting $DEST"
        mount -t ext4 -o ro,noatime $LOOPDEV $DEST
      fi
      rm -f /apex/apex_payload.img
    elif [ -d $APEX ]; then
      # APEX folders, bind mount directory
      if [ -f $APEX/apex_manifest.json ]; then
        DEST=/apex/$(sed -n $PATTERN $APEX/apex_manifest.json)
      elif [ -f $APEX/apex_manifest.pb ]; then
        DEST=/apex/$(strings $APEX/apex_manifest.pb | head -n 1)
      else
        continue
      fi
      mkdir -p $DEST
      ui_print "- Mounting $DEST"
      mount -o bind $APEX $DEST
    fi
  done
  export ANDROID_RUNTIME_ROOT=/apex/com.android.runtime
  export ANDROID_TZDATA_ROOT=/apex/com.android.tzdata
  export ANDROID_ART_ROOT=/apex/com.android.art
  export ANDROID_I18N_ROOT=/apex/com.android.i18n
  local APEXJARS=$(find /apex -name '*.jar' | sort | tr '\n' ':')
  local FWK=/system/framework
  export BOOTCLASSPATH=${APEXJARS}\
$FWK/framework.jar:$FWK/ext.jar:$FWK/telephony-common.jar:\
$FWK/voip-common.jar:$FWK/ims-common.jar:$FWK/telephony-ext.jar
}

umount_apex() {
  [ -d /apex ] || return
  umount -l /apex
  for loop in /dev/block/loop*; do
    losetup -d $loop 2>/dev/null
  done
  unset ANDROID_RUNTIME_ROOT
  unset ANDROID_TZDATA_ROOT
  unset ANDROID_ART_ROOT
  unset ANDROID_I18N_ROOT
  unset BOOTCLASSPATH
}

get_flags() {
  # override variables
  getvar KEEPVERITY
  getvar KEEPFORCEENCRYPT
  getvar RECOVERYMODE
  if [ -z $KEEPVERITY ]; then
    if $SYSTEM_ROOT; then
      KEEPVERITY=true
      ui_print "- System-as-root, keep dm/avb-verity"
    else
      KEEPVERITY=false
    fi
  fi
  ISENCRYPTED=false
  grep ' /data ' /proc/mounts | grep -q 'dm-' && ISENCRYPTED=true
  [ "$(getprop ro.crypto.state)" = "encrypted" ] && ISENCRYPTED=true
  if [ -z $KEEPFORCEENCRYPT ]; then
    # No data access means unable to decrypt in recovery
    if $ISENCRYPTED || ! $DATA; then
      KEEPFORCEENCRYPT=true
      ui_print "- Encrypted data, keep forceencrypt"
    else
      KEEPFORCEENCRYPT=false
    fi
  fi
  [ -z $RECOVERYMODE ] && RECOVERYMODE=false
}

find_boot_image() {
  BOOTIMAGE=
  if $RECOVERYMODE; then
    BOOTIMAGE=`find_block recovery_ramdisk$SLOT recovery$SLOT sos`
  elif [ ! -z $SLOT ]; then
    BOOTIMAGE=`find_block ramdisk$SLOT recovery_ramdisk$SLOT boot$SLOT`
  else
    BOOTIMAGE=`find_block ramdisk recovery_ramdisk kern-a android_boot kernel bootimg boot lnx boot_a`
  fi
  if [ -z $BOOTIMAGE ]; then
    # Lets see what fstabs tells me
    BOOTIMAGE=`grep -v '#' /etc/*fstab* | grep -E '/boot(img)?[^a-zA-Z]' | grep -oE '/dev/[a-zA-Z0-9_./-]*' | head -n 1`
  fi
}

flash_image() {
  case "$1" in
    *.gz) CMD1="gzip -d < '$1' 2>/dev/null";;
    *)    CMD1="cat '$1'";;
  esac
  if $BOOTSIGNED; then
    CMD2="$BOOTSIGNER -sign"
    ui_print "- Sign image with verity keys"
  else
    CMD2="cat -"
  fi
  if [ -b "$2" ]; then
    local img_sz=$(stat -c '%s' "$1")
    local blk_sz=$(blockdev --getsize64 "$2")
    [ "$img_sz" -gt "$blk_sz" ] && return 1
    blockdev --setrw "$2"
    local blk_ro=$(blockdev --getro "$2")
    [ "$blk_ro" -eq 1 ] && return 2
    eval "$CMD1" | eval "$CMD2" | cat - /dev/zero > "$2" 2>/dev/null
  elif [ -c "$2" ]; then
    flash_eraseall "$2" >&2
    eval "$CMD1" | eval "$CMD2" | nandwrite -p "$2" - >&2
  else
    ui_print "- Not block or char device, storing image"
    eval "$CMD1" | eval "$CMD2" > "$2" 2>/dev/null
  fi
  return 0
}

# Common installation script for flash_script.sh and addon.d.sh
install_magisk() {
  cd $MAGISKBIN

  if [ ! -c $BOOTIMAGE ]; then
    eval $BOOTSIGNER -verify < $BOOTIMAGE && BOOTSIGNED=true
    $BOOTSIGNED && ui_print "- Boot image is signed with AVB 1.0"
  fi

  # Source the boot patcher
  SOURCEDMODE=true
  . ./boot_patch.sh "$BOOTIMAGE"

  ui_print "- Flashing new boot image"
  flash_image new-boot.img "$BOOTIMAGE"
  case $? in
    1)
      abort "! Insufficient partition size"
      ;;
    2)
      abort "! $BOOTIMAGE is read only"
      ;;
  esac

  ./magiskboot cleanup
  rm -f new-boot.img

  run_migrations
}

sign_chromeos() {
  ui_print "- Signing ChromeOS boot image"

  echo > empty
  ./chromeos/futility vbutil_kernel --pack new-boot.img.signed \
  --keyblock ./chromeos/kernel.keyblock --signprivate ./chromeos/kernel_data_key.vbprivk \
  --version 1 --vmlinuz new-boot.img --config empty --arch arm --bootloader empty --flags 0x1

  rm -f empty new-boot.img
  mv new-boot.img.signed new-boot.img
}

remove_system_su() {
  if [ -f /system/bin/su -o -f /system/xbin/su ] && [ ! -f /su/bin/su ]; then
    ui_print "- Removing system installed root"
    blockdev --setrw /dev/block/mapper/system$SLOT 2>/dev/null
    mount -o rw,remount /system
    # SuperSU
    if [ -e /system/bin/.ext/.su ]; then
      mv -f /system/bin/app_process32_original /system/bin/app_process32 2>/dev/null
      mv -f /system/bin/app_process64_original /system/bin/app_process64 2>/dev/null
      mv -f /system/bin/install-recovery_original.sh /system/bin/install-recovery.sh 2>/dev/null
      cd /system/bin
      if [ -e app_process64 ]; then
        ln -sf app_process64 app_process
      elif [ -e app_process32 ]; then
        ln -sf app_process32 app_process
      fi
    fi
    rm -rf /system/.pin /system/bin/.ext /system/etc/.installed_su_daemon /system/etc/.has_su_daemon \
    /system/xbin/daemonsu /system/xbin/su /system/xbin/sugote /system/xbin/sugote-mksh /system/xbin/supolicy \
    /system/bin/app_process_init /system/bin/su /cache/su /system/lib/libsupol.so /system/lib64/libsupol.so \
    /system/su.d /system/etc/install-recovery.sh /system/etc/init.d/99SuperSUDaemon /cache/install-recovery.sh \
    /system/.supersu /cache/.supersu /data/.supersu \
    /system/app/Superuser.apk /system/app/SuperSU /cache/Superuser.apk
  elif [ -f /cache/su.img -o -f /data/su.img -o -d /data/adb/su -o -d /data/su ]; then
    ui_print "- Removing systemless installed root"
    umount -l /su 2>/dev/null
    rm -rf /cache/su.img /data/su.img /data/adb/su /data/adb/suhide /data/su /cache/.supersu /data/.supersu \
    /cache/supersu_install /data/supersu_install
  fi
}

api_level_arch_detect() {
  API=$(grep_get_prop ro.build.version.sdk)
  ABI=$(grep_get_prop ro.product.cpu.abi)
  if [ "$ABI" = "x86" ]; then
    ARCH=x86
    ABI32=x86
    IS64BIT=false
  elif [ "$ABI" = "arm64-v8a" ]; then
    ARCH=arm64
    ABI32=armeabi-v7a
    IS64BIT=true
  elif [ "$ABI" = "x86_64" ]; then
    ARCH=x64
    ABI32=x86
    IS64BIT=true
  else
    ARCH=arm
    ABI=armeabi-v7a
    ABI32=armeabi-v7a
    IS64BIT=false
  fi
}

check_data() {
  DATA=false
  DATA_DE=false
  if grep ' /data ' /proc/mounts | grep -vq 'tmpfs'; then
    # Test if data is writable
    touch /data/.rw && rm /data/.rw && DATA=true
    # Test if data is decrypted
    $DATA && [ -d /data/adb ] && touch /data/adb/.rw && rm /data/adb/.rw && DATA_DE=true
    $DATA_DE && [ -d /data/adb/magisk ] || mkdir /data/adb/magisk || DATA_DE=false
  fi
  NVBASE=/data
  $DATA || NVBASE=/cache/data_adb
  $DATA_DE && NVBASE=/data/adb
  resolve_vars
}

find_magisk_apk() {
  local DBAPK
  [ -z $APK ] && APK=/data/adb/magisk.apk
  [ -f $APK ] || APK=/data/magisk/magisk.apk
  [ -f $APK ] || APK=/data/app/com.topjohnwu.magisk*/*.apk
  [ -f $APK ] || APK=/data/app/*/com.topjohnwu.magisk*/*.apk
  if [ ! -f $APK ]; then
    DBAPK=$(magisk --sqlite "SELECT value FROM strings WHERE key='requester'" 2>/dev/null | cut -d= -f2)
    [ -z $DBAPK ] && DBAPK=$(strings /data/adb/magisk.db | grep -oE 'requester..*' | cut -c10-)
    [ -z $DBAPK ] || APK=/data/user_de/*/$DBAPK/dyn/*.apk
    [ -f $APK ] || [ -z $DBAPK ] || APK=/data/app/$DBAPK*/*.apk
    [ -f $APK ] || [ -z $DBAPK ] || APK=/data/app/*/$DBAPK*/*.apk
  fi
  [ -f $APK ] || ui_print "! Unable to detect Magisk app APK for BootSigner"
}

run_migrations() {
  local LOCSHA1
  local TARGET
  # Legacy app installation
  local BACKUP=/data/adb/magisk/stock_boot*.gz
  if [ -f $BACKUP ]; then
    cp $BACKUP /data
    rm -f $BACKUP
  fi

  # Legacy backup
  for gz in /data/stock_boot*.gz; do
    [ -f $gz ] || break
    LOCSHA1=`basename $gz | sed -e 's/stock_boot_//' -e 's/.img.gz//'`
    [ -z $LOCSHA1 ] && break
    mkdir /data/magisk_backup_${LOCSHA1} 2>/dev/null
    mv $gz /data/magisk_backup_${LOCSHA1}/boot.img.gz
  done

  # Stock backups
  LOCSHA1=$SHA1
  for name in boot dtb dtbo dtbs; do
    BACKUP=/data/adb/magisk/stock_${name}.img
    [ -f $BACKUP ] || continue
    if [ $name = 'boot' ]; then
      LOCSHA1=`$MAGISKBIN/magiskboot sha1 $BACKUP`
      mkdir /data/magisk_backup_${LOCSHA1} 2>/dev/null
    fi
    TARGET=/data/magisk_backup_${LOCSHA1}/${name}.img
    cp $BACKUP $TARGET
    rm -f $BACKUP
    gzip -9f $TARGET
  done
}

copy_sepolicy_rules() {
  # Remove all existing rule folders
  rm -rf /data/unencrypted/magisk /cache/magisk /metadata/magisk /persist/magisk /mnt/vendor/persist/magisk

  # Find current active RULESDIR
  local RULESDIR
  local active_dir=$(magisk --path)/.magisk/mirror/sepolicy.rules
  if [ -L $active_dir ]; then
    RULESDIR=$(readlink $active_dir)
    [ "${RULESDIR:0:1}" != "/" ] && RULESDIR="$(magisk --path)/.magisk/mirror/$RULESDIR"
  elif [ -d /data/unencrypted ] && ! grep ' /data ' /proc/mounts | grep -qE 'dm-|f2fs'; then
    RULESDIR=/data/unencrypted/magisk
  elif grep -q ' /cache ' /proc/mounts; then
    RULESDIR=/cache/magisk
  elif grep -q ' /persist ' /proc/mounts; then
    RULESDIR=/persist/magisk
  elif grep -q ' /mnt/vendor/persist ' /proc/mounts; then
    RULESDIR=/mnt/vendor/persist/magisk
  elif grep -q ' /metadata ' /proc/mounts; then
    RULESDIR=/metadata/magisk
  else
    return
  fi
  ui_print "- Sepolicy rules dir is $RULESDIR"

  # Copy all enabled sepolicy.rule
  for r in /data/adb/modules*/*/sepolicy.rule; do
    [ -f "$r" ] || continue
    local MODDIR=${r%/*}
    [ -f $MODDIR/disable ] && continue
    [ -f $MODDIR/remove ] && continue
    local MODNAME=${MODDIR##*/}
    mkdir -p $RULESDIR/$MODNAME
    cp -f $r $RULESDIR/$MODNAME/sepolicy.rule
  done
}

#################
# Module Related
#################

set_perm() {
  chown $2:$3 $1 || return 1
  chmod $4 $1 || return 1
  CON=$5
  [ -z $CON ] && CON=u:object_r:system_file:s0
  chcon $CON $1 || return 1
}

set_perm_recursive() {
  find $1 -type d 2>/dev/null | while read dir; do
    set_perm $dir $2 $3 $4 $6
  done
  find $1 -type f -o -type l 2>/dev/null | while read file; do
    set_perm $file $2 $3 $5 $6
  done
}

mktouch() {
  mkdir -p ${1%/*} 2>/dev/null
  [ -z $2 ] && touch $1 || echo $2 > $1
  chmod 644 $1
}

request_size_check() {
  reqSizeM=`du -ms "$1" | cut -f1`
}

request_zip_size_check() {
  reqSizeM=`unzip -l "$1" | tail -n 1 | awk '{ print int(($1 - 1) / 1048576 + 1) }'`
}

boot_actions() { return; }

# Require ZIPFILE to be set
is_legacy_script() {
  unzip -l "$ZIPFILE" install.sh | grep -q install.sh
  return $?
}

# Require OUTFD, ZIPFILE to be set
install_module() {
  rm -rf $TMPDIR
  mkdir -p $TMPDIR
  cd $TMPDIR

  setup_flashable
  mount_partitions
  api_level_arch_detect

  # Setup busybox and binaries
  if $BOOTMODE; then
    boot_actions
  else
    recovery_actions
  fi

  # Extract prop file
  unzip -o "$ZIPFILE" module.prop -d $TMPDIR >&2
  [ ! -f $TMPDIR/module.prop ] && abort "! Unable to extract zip file!"

  local MODDIRNAME=modules
  $BOOTMODE && MODDIRNAME=modules_update
  local MODULEROOT=$NVBASE/$MODDIRNAME
  MODID=`grep_prop id $TMPDIR/module.prop`
  MODNAME=`grep_prop name $TMPDIR/module.prop`
  MODAUTH=`grep_prop author $TMPDIR/module.prop`
  MODPATH=$MODULEROOT/$MODID

  # Create mod paths
  rm -rf $MODPATH
  mkdir -p $MODPATH

  if is_legacy_script; then
    unzip -oj "$ZIPFILE" module.prop install.sh uninstall.sh 'common/*' -d $TMPDIR >&2

    # Load install script
    . $TMPDIR/install.sh

    # Callbacks
    print_modname
    on_install

    [ -f $TMPDIR/uninstall.sh ] && cp -af $TMPDIR/uninstall.sh $MODPATH/uninstall.sh
    $SKIPMOUNT && touch $MODPATH/skip_mount
    $PROPFILE && cp -af $TMPDIR/system.prop $MODPATH/system.prop
    cp -af $TMPDIR/module.prop $MODPATH/module.prop
    $POSTFSDATA && cp -af $TMPDIR/post-fs-data.sh $MODPATH/post-fs-data.sh
    $LATESTARTSERVICE && cp -af $TMPDIR/service.sh $MODPATH/service.sh

    ui_print "- Setting permissions"
    set_permissions
  else
    print_title "$MODNAME" "by $MODAUTH"
    print_title "Powered by Magisk"

    unzip -o "$ZIPFILE" customize.sh -d $MODPATH >&2

    if ! grep -q '^SKIPUNZIP=1$' $MODPATH/customize.sh 2>/dev/null; then
      ui_print "- Extracting module files"
      unzip -o "$ZIPFILE" -x 'META-INF/*' -d $MODPATH >&2

      # Default permissions
      set_perm_recursive $MODPATH 0 0 0755 0644
    fi

    # Load customization script
    [ -f $MODPATH/customize.sh ] && . $MODPATH/customize.sh
  fi

  # Handle replace folders
  for TARGET in $REPLACE; do
    ui_print "- Replace target: $TARGET"
    mktouch $MODPATH$TARGET/.replace
  done

  if $BOOTMODE; then
    # Update info for Magisk app
    mktouch $NVBASE/modules/$MODID/update
    rm -rf $NVBASE/modules/$MODID/remove 2>/dev/null
    rm -rf $NVBASE/modules/$MODID/disable 2>/dev/null
    cp -af $MODPATH/module.prop $NVBASE/modules/$MODID/module.prop
  fi

  # Copy over custom sepolicy rules
  if [ -f $MODPATH/sepolicy.rule ]; then
    ui_print "- Installing custom sepolicy rules"
    copy_sepolicy_rules
  fi

  # Remove stuff that doesn't belong to modules and clean up any empty directories
  rm -rf \
  $MODPATH/system/placeholder $MODPATH/customize.sh \
  $MODPATH/README.md $MODPATH/.git*
  rmdir -p $MODPATH

  cd /
  $BOOTMODE || recovery_cleanup
  rm -rf $TMPDIR

  ui_print "- Done"
}

##########
# Presets
##########

# Detect whether in boot mode
[ -z $BOOTMODE ] && ps | grep zygote | grep -qv grep && BOOTMODE=true
[ -z $BOOTMODE ] && ps -A 2>/dev/null | grep zygote | grep -qv grep && BOOTMODE=true
[ -z $BOOTMODE ] && BOOTMODE=false

NVBASE=/data/adb
TMPDIR=/dev/tmp

# Bootsigner related stuff
BOOTSIGNERCLASS=com.topjohnwu.signing.SignBoot
BOOTSIGNER='/system/bin/dalvikvm -Xnoimage-dex2oat -cp $APK $BOOTSIGNERCLASS'
BOOTSIGNED=false

resolve_vars
