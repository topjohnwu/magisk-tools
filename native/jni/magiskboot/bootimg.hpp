#pragma once

#include <stdint.h>
#include <utility>
#include <bitset>
#include "format.hpp"

/******************
 * Special Headers
 *****************/

struct mtk_hdr {
    uint32_t magic;         /* MTK magic */
    uint32_t size;          /* Size of the content */
    char name[32];          /* The type of the header */

    char padding[472];      /* Padding to 512 bytes */
} __attribute__((packed));

struct dhtb_hdr {
    char magic[8];          /* DHTB magic */
    uint8_t checksum[40];   /* Payload SHA256, whole image + SEANDROIDENFORCE + 0xFFFFFFFF */
    uint32_t size;          /* Payload size, whole image + SEANDROIDENFORCE + 0xFFFFFFFF */

    char padding[460];      /* Padding to 512 bytes */
} __attribute__((packed));

struct blob_hdr {
    char secure_magic[20];  /* "-SIGNED-BY-SIGNBLOB-" */
    uint32_t datalen;       /* 0x00000000 */
    uint32_t signature;     /* 0x00000000 */
    char magic[16];         /* "MSM-RADIO-UPDATE" */
    uint32_t hdr_version;   /* 0x00010000 */
    uint32_t hdr_size;      /* Size of header */
    uint32_t part_offset;   /* Same as size */
    uint32_t num_parts;     /* Number of partitions */
    uint32_t unknown[7];    /* All 0x00000000 */
    char name[4];           /* Name of partition */
    uint32_t offset;        /* offset in blob where this partition starts */
    uint32_t size;          /* Size of data */
    uint32_t version;       /* 0x00000001 */
} __attribute__((packed));

/**************
 * AVB Headers
 **************/

#define AVB_FOOTER_MAGIC_LEN 4
#define AVB_MAGIC_LEN 4
#define AVB_RELEASE_STRING_SIZE 48

// https://android.googlesource.com/platform/external/avb/+/refs/heads/android11-release/libavb/avb_footer.h
struct AvbFooter {
    uint8_t magic[AVB_FOOTER_MAGIC_LEN];
    uint32_t version_major;
    uint32_t version_minor;
    uint64_t original_image_size;
    uint64_t vbmeta_offset;
    uint64_t vbmeta_size;
    uint8_t reserved[28];
} __attribute__((packed));

// https://android.googlesource.com/platform/external/avb/+/refs/heads/android11-release/libavb/avb_vbmeta_image.h
struct AvbVBMetaImageHeader {
    uint8_t magic[AVB_MAGIC_LEN];
    uint32_t required_libavb_version_major;
    uint32_t required_libavb_version_minor;
    uint64_t authentication_data_block_size;
    uint64_t auxiliary_data_block_size;
    uint32_t algorithm_type;
    uint64_t hash_offset;
    uint64_t hash_size;
    uint64_t signature_offset;
    uint64_t signature_size;
    uint64_t public_key_offset;
    uint64_t public_key_size;
    uint64_t public_key_metadata_offset;
    uint64_t public_key_metadata_size;
    uint64_t descriptors_offset;
    uint64_t descriptors_size;
    uint64_t rollback_index;
    uint32_t flags;
    uint32_t rollback_index_location;
    uint8_t release_string[AVB_RELEASE_STRING_SIZE];
    uint8_t reserved[80];
} __attribute__((packed));

/*********************
 * Boot Image Headers
 *********************/

#define BOOT_MAGIC_SIZE 8
#define BOOT_NAME_SIZE 16
#define BOOT_ID_SIZE 32
#define BOOT_ARGS_SIZE 512
#define BOOT_EXTRA_ARGS_SIZE 1024
#define VENDOR_BOOT_ARGS_SIZE 2048
#define VENDOR_RAMDISK_NAME_SIZE 32
#define VENDOR_RAMDISK_TABLE_ENTRY_BOARD_ID_SIZE 16

/*
 * +-----------------+
 * | boot header     | 1 page
 * +-----------------+
 * | kernel          | n pages
 * +-----------------+
 * | ramdisk         | m pages
 * +-----------------+
 * | second stage    | o pages
 * +-----------------+
 * | extra blob      | x pages (non standard)
 * +-----------------+
 * | recovery dtbo   | p pages
 * +-----------------+
 * | dtb             | q pages
 * +-----------------+
 *
 * n = (kernel_size + page_size - 1) / page_size
 * m = (ramdisk_size + page_size - 1) / page_size
 * o = (second_size + page_size - 1) / page_size
 * p = (recovery_dtbo_size + page_size - 1) / page_size
 * q = (dtb_size + page_size - 1) / page_size
 * x = (extra_size + page_size - 1) / page_size
 */

struct boot_img_hdr_common {
    char magic[BOOT_MAGIC_SIZE];

    uint32_t kernel_size;  /* size in bytes */
    uint32_t kernel_addr;  /* physical load addr */

    uint32_t ramdisk_size; /* size in bytes */
    uint32_t ramdisk_addr; /* physical load addr */

    uint32_t second_size;  /* size in bytes */
    uint32_t second_addr;  /* physical load addr */
} __attribute__((packed));

struct boot_img_hdr_v0 : public boot_img_hdr_common {
    uint32_t tags_addr;    /* physical addr for kernel tags */
    uint32_t page_size;    /* flash page size we assume */

    // In header v1, this field is used for header version
    // However, on some devices like Samsung, this field is used to store DTB
    // We treat this field differently based on its value
    union {
        uint32_t header_version;  /* the version of the header */
        uint32_t extra_size;      /* extra blob size in bytes */
    };

    // Operating system version and security patch level.
    // For version "A.B.C" and patch level "Y-M-D":
    //   (7 bits for each of A, B, C; 7 bits for (Y-2000), 4 bits for M)
    //   os_version = A[31:25] B[24:18] C[17:11] (Y-2000)[10:4] M[3:0]
    uint32_t os_version;

    char name[BOOT_NAME_SIZE];  /* asciiz product name */
    char cmdline[BOOT_ARGS_SIZE];
    char id[BOOT_ID_SIZE];      /* timestamp / checksum / sha1 / etc */

    // Supplemental command line data; kept here to maintain
    // binary compatibility with older versions of mkbootimg.
    char extra_cmdline[BOOT_EXTRA_ARGS_SIZE];
} __attribute__((packed));

struct boot_img_hdr_v1 : public boot_img_hdr_v0 {
    uint32_t recovery_dtbo_size;    /* size in bytes for recovery DTBO/ACPIO image */
    uint64_t recovery_dtbo_offset;  /* offset to recovery dtbo/acpio in boot image */
    uint32_t header_size;
} __attribute__((packed));

struct boot_img_hdr_v2 : public boot_img_hdr_v1 {
    uint32_t dtb_size;  /* size in bytes for DTB image */
    uint64_t dtb_addr;  /* physical load address for DTB image */
} __attribute__((packed));

// Default to hdr v2
using boot_img_hdr = boot_img_hdr_v2;

// Special Samsung header
struct boot_img_hdr_pxa : public boot_img_hdr_common {
    uint32_t extra_size;   /* extra blob size in bytes */
    uint32_t unknown;
    uint32_t tags_addr;    /* physical addr for kernel tags */
    uint32_t page_size;    /* flash page size we assume */

    char name[24];         /* asciiz product name */
    char cmdline[BOOT_ARGS_SIZE];
    char id[BOOT_ID_SIZE]; /* timestamp / checksum / sha1 / etc */

    char extra_cmdline[BOOT_EXTRA_ARGS_SIZE];
} __attribute__((packed));

/* When the boot image header has a version of 3, the structure of the boot
 * image is as follows:
 *
 * +---------------------+
 * | boot header         | 4096 bytes
 * +---------------------+
 * | kernel              | m pages
 * +---------------------+
 * | ramdisk             | n pages
 * +---------------------+
 *
 * m = (kernel_size + 4096 - 1) / 4096
 * n = (ramdisk_size + 4096 - 1) / 4096
 *
 * Note that in version 3 of the boot image header, page size is fixed at 4096 bytes.
 *
 * The structure of the vendor boot image (introduced with version 3 and
 * required to be present when a v3 boot image is used) is as follows:
 *
 * +---------------------+
 * | vendor boot header  | o pages
 * +---------------------+
 * | vendor ramdisk      | p pages
 * +---------------------+
 * | dtb                 | q pages
 * +---------------------+
 *
 * o = (2112 + page_size - 1) / page_size
 * p = (ramdisk_size + page_size - 1) / page_size
 * q = (dtb_size + page_size - 1) / page_size
 */

struct boot_img_hdr_v3 {
    uint8_t magic[BOOT_MAGIC_SIZE];

    uint32_t kernel_size;  /* size in bytes */
    uint32_t ramdisk_size; /* size in bytes */
    uint32_t os_version;
    uint32_t header_size;
    uint32_t reserved[4];

    uint32_t header_version;

    char cmdline[BOOT_ARGS_SIZE + BOOT_EXTRA_ARGS_SIZE];
} __attribute__((packed));

struct boot_img_hdr_vnd_v3 {
    // Must be VENDOR_BOOT_MAGIC.
    uint8_t magic[BOOT_MAGIC_SIZE];
    // Version of the vendor boot image header.
    uint32_t header_version;
    uint32_t page_size;     /* flash page size we assume */
    uint32_t kernel_addr;   /* physical load addr */
    uint32_t ramdisk_addr;  /* physical load addr */
    uint32_t ramdisk_size;  /* size in bytes */
    char cmdline[VENDOR_BOOT_ARGS_SIZE];
    uint32_t tags_addr;     /* physical addr for kernel tags (if required) */
    char name[BOOT_NAME_SIZE]; /* asciiz product name */
    uint32_t header_size;
    uint32_t dtb_size;      /* size in bytes for DTB image */
    uint64_t dtb_addr;      /* physical load address for DTB image */
} __attribute__((packed));

/* When the boot image header has a version of 4, the structure of the boot
 * image is as follows:
 *
 * +---------------------+
 * | boot header         | 4096 bytes
 * +---------------------+
 * | kernel              | m pages
 * +---------------------+
 * | ramdisk             | n pages
 * +---------------------+
 * | boot signature      | g pages
 * +---------------------+
 *
 * m = (kernel_size + 4096 - 1) / 4096
 * n = (ramdisk_size + 4096 - 1) / 4096
 * g = (signature_size + 4096 - 1) / 4096
 *
 * Note that in version 4 of the boot image header, page size is fixed at 4096
 * bytes.
 *
 * The structure of the vendor boot image version 4, which is required to be
 * present when a version 4 boot image is used, is as follows:
 *
 * +------------------------+
 * | vendor boot header     | o pages
 * +------------------------+
 * | vendor ramdisk section | p pages
 * +------------------------+
 * | dtb                    | q pages
 * +------------------------+
 * | vendor ramdisk table   | r pages
 * +------------------------+
 * | bootconfig             | s pages
 * +------------------------+
 *
 * o = (2128 + page_size - 1) / page_size
 * p = (vendor_ramdisk_size + page_size - 1) / page_size
 * q = (dtb_size + page_size - 1) / page_size
 * r = (vendor_ramdisk_table_size + page_size - 1) / page_size
 * s = (vendor_bootconfig_size + page_size - 1) / page_size
 *
 * Note that in version 4 of the vendor boot image, multiple vendor ramdisks can
 * be included in the vendor boot image. The bootloader can select a subset of
 * ramdisks to load at runtime. To help the bootloader select the ramdisks, each
 * ramdisk is tagged with a type tag and a set of hardware identifiers
 * describing the board, soc or platform that this ramdisk is intended for.
 *
 * The vendor ramdisk section is consist of multiple ramdisk images concatenated
 * one after another, and vendor_ramdisk_size is the size of the section, which
 * is the total size of all the ramdisks included in the vendor boot image.
 *
 * The vendor ramdisk table holds the size, offset, type, name and hardware
 * identifiers of each ramdisk. The type field denotes the type of its content.
 * The vendor ramdisk names are unique. The hardware identifiers are specified
 * in the board_id field in each table entry. The board_id field is consist of a
 * vector of unsigned integer words, and the encoding scheme is defined by the
 * hardware vendor.
 *
 * For the different type of ramdisks, there are:
 *    - VENDOR_RAMDISK_TYPE_NONE indicates the value is unspecified.
 *    - VENDOR_RAMDISK_TYPE_PLATFORM ramdisks contain platform specific bits, so
 *      the bootloader should always load these into memory.
 *    - VENDOR_RAMDISK_TYPE_RECOVERY ramdisks contain recovery resources, so
 *      the bootloader should load these when booting into recovery.
 *    - VENDOR_RAMDISK_TYPE_DLKM ramdisks contain dynamic loadable kernel
 *      modules.
 *
 * Version 4 of the vendor boot image also adds a bootconfig section to the end
 * of the image. This section contains Boot Configuration parameters known at
 * build time. The bootloader is responsible for placing this section directly
 * after the generic ramdisk, followed by the bootconfig trailer, before
 * entering the kernel.
 */
struct boot_img_hdr_v4 : public boot_img_hdr_v3 {
    uint32_t signature_size; /* size in bytes */
} __attribute__((packed));

struct boot_img_hdr_vnd_v4 : public boot_img_hdr_vnd_v3 {
    uint32_t vendor_ramdisk_table_size;       /* size in bytes for the vendor ramdisk table */
    uint32_t vendor_ramdisk_table_entry_num;  /* number of entries in the vendor ramdisk table */
    uint32_t vendor_ramdisk_table_entry_size; /* size in bytes for a vendor ramdisk table entry */
    uint32_t bootconfig_size; /* size in bytes for the bootconfig section */
} __attribute__((packed));

struct vendor_ramdisk_table_entry_v4 {
    uint32_t ramdisk_size;   /* size in bytes for the ramdisk image */
    uint32_t ramdisk_offset; /* offset to the ramdisk image in vendor ramdisk section */
    uint32_t ramdisk_type;   /* type of the ramdisk */
    uint8_t ramdisk_name[VENDOR_RAMDISK_NAME_SIZE]; /* asciiz ramdisk name */

    // Hardware identifiers describing the board, soc or platform which this
    // ramdisk is intended to be loaded on.
    uint32_t board_id[VENDOR_RAMDISK_TABLE_ENTRY_BOARD_ID_SIZE];
} __attribute__((packed));

/*******************************
 * Polymorphic Universal Header
 *******************************/

#define decl_var(name, len) \
virtual uint##len##_t &name() { j##len = 0; return j##len; }
#define decl_val(name, type) \
virtual type name() { return 0; }

struct dyn_img_hdr {

    // Standard entries
    decl_var(kernel_size, 32)
    decl_var(ramdisk_size, 32)
    decl_var(second_size, 32)
    decl_var(page_size, 32)
    decl_val(header_version, uint32_t)
    decl_var(extra_size, 32)
    decl_var(os_version, 32)
    decl_val(name, char *)
    decl_val(cmdline, char *)
    decl_val(id, char *)
    decl_val(extra_cmdline, char *)
    uint32_t kernel_dt_size = 0;

    // v1/v2 specific
    decl_var(recovery_dtbo_size, 32)
    decl_var(recovery_dtbo_offset, 64)
    decl_var(header_size, 32)
    decl_var(dtb_size, 32)

    virtual ~dyn_img_hdr() {
        free(raw);
    }

    virtual size_t hdr_size() = 0;
    virtual size_t hdr_space() { return page_size(); }
    virtual dyn_img_hdr *clone() = 0;

    const void *raw_hdr() const { return raw; }
    void print();
    void dump_hdr_file();
    void load_hdr_file();

protected:
    union {
        // Main header could be either AOSP or PXA
        boot_img_hdr_v2 *v2_hdr;     /* AOSP v2 header */
        boot_img_hdr_v3 *v3_hdr;     /* AOSP v3 header */
        boot_img_hdr_v4 *v4_hdr;     /* AOSP v4 header */
        boot_img_hdr_pxa *hdr_pxa;   /* Samsung PXA header */
        boot_img_hdr_vnd_v3 *v3_vnd; /* AOSP vendor v3 header */
        boot_img_hdr_vnd_v4 *v4_vnd; /* AOSP vendor v4 header */
        void *raw;                   /* Raw pointer */
    };

private:
    // Junk for references
    static uint32_t j32;
    static uint64_t j64;
};

#undef decl_var
#undef decl_val

#define __impl_cls(name, hdr) \
protected: name() = default; \
public: \
name(void *ptr) { \
    raw = xmalloc(sizeof(hdr)); \
    memcpy(raw, ptr, sizeof(hdr)); \
} \
size_t hdr_size() override { return sizeof(hdr); } \
dyn_img_hdr *clone() override { \
    auto p = new name(this->raw); \
    p->kernel_dt_size = kernel_dt_size; \
    return p; \
};

#define __impl_val(name, hdr_name) \
decltype(std::declval<dyn_img_hdr>().name()) name() override { return hdr_name->name; }

#define impl_cls(ver) __impl_cls(dyn_img_##ver, boot_img_hdr_##ver)
#define impl_val(name) __impl_val(name, v2_hdr)

struct dyn_img_common : public dyn_img_hdr {
    impl_val(kernel_size)
    impl_val(ramdisk_size)
    impl_val(second_size)
};

struct dyn_img_v0 : public dyn_img_common {
    impl_cls(v0)

    impl_val(page_size)
    impl_val(extra_size)
    impl_val(os_version)
    impl_val(name)
    impl_val(cmdline)
    impl_val(id)
    impl_val(extra_cmdline)
};

struct dyn_img_v1 : public dyn_img_v0 {
    impl_cls(v1)

    impl_val(header_version)
    impl_val(recovery_dtbo_size)
    impl_val(recovery_dtbo_offset)
    impl_val(header_size)

    uint32_t &extra_size() override { return dyn_img_hdr::extra_size(); }
};

struct dyn_img_v2 : public dyn_img_v1 {
    impl_cls(v2)

    impl_val(dtb_size)
};

#undef impl_val
#define impl_val(name) __impl_val(name, hdr_pxa)

struct dyn_img_pxa : public dyn_img_common {
    impl_cls(pxa)

    impl_val(extra_size)
    impl_val(page_size)
    impl_val(name)
    impl_val(cmdline)
    impl_val(id)
    impl_val(extra_cmdline)
};

#undef impl_val
#define impl_val(name) __impl_val(name, v3_hdr)

struct dyn_img_v3 : public dyn_img_hdr {
    impl_cls(v3)

    impl_val(kernel_size)
    impl_val(ramdisk_size)
    impl_val(os_version)
    impl_val(header_size)
    impl_val(header_version)
    impl_val(cmdline)

    // Make API compatible
    uint32_t &page_size() override { page_sz = 4096; return page_sz; }
    char *extra_cmdline() override { return &v3_hdr->cmdline[BOOT_ARGS_SIZE]; }

private:
    uint32_t page_sz = 4096;
};

#undef impl_val
#define impl_val(name) __impl_val(name, v3_vnd)

struct dyn_img_vnd_v3 : public dyn_img_hdr {
    impl_cls(vnd_v3)

    impl_val(header_version)
    impl_val(page_size)
    impl_val(ramdisk_size)
    impl_val(cmdline)
    impl_val(name)
    impl_val(header_size)
    impl_val(dtb_size)

    size_t hdr_space() override { auto sz = page_size(); return do_align(hdr_size(), sz); }

    // Make API compatible
    char *extra_cmdline() override { return &v3_vnd->cmdline[BOOT_ARGS_SIZE]; }
};

#undef impl_val
#define impl_val(name) __impl_val(name, v4_hdr)

struct dyn_img_v4 : public dyn_img_v3 {
    impl_cls(v4)
};

#undef impl_val
#define impl_val(name) __impl_val(name, v4_vnd)

struct dyn_img_vnd_v4 : public dyn_img_vnd_v3 {
    impl_cls(vnd_v4)
};

#undef __impl_cls
#undef __impl_val
#undef impl_cls
#undef impl_val

/******************
 * Full Boot Image
 ******************/

enum {
    MTK_KERNEL,
    MTK_RAMDISK,
    CHROMEOS_FLAG,
    DHTB_FLAG,
    SEANDROID_FLAG,
    LG_BUMP_FLAG,
    SHA256_FLAG,
    BLOB_FLAG,
    NOOKHD_FLAG,
    ACCLAIM_FLAG,
    AVB_FLAG,
    BOOT_FLAGS_MAX
};

struct boot_img {
    // Memory map of the whole image
    uint8_t *map_addr;
    size_t map_size;

    // Android image header
    dyn_img_hdr *hdr;

    // Flags to indicate the state of current boot image
    std::bitset<BOOT_FLAGS_MAX> flags;

    // The format of kernel, ramdisk and extra
    format_t k_fmt = UNKNOWN;
    format_t r_fmt = UNKNOWN;
    format_t e_fmt = UNKNOWN;

    /***************************************************
     * Following pointers points within the mmap region
     ***************************************************/

    // MTK headers
    mtk_hdr *k_hdr;
    mtk_hdr *r_hdr;

    // Pointer to dtb that is embedded in kernel
    uint8_t *kernel_dtb;

    // Pointer to end of image
    uint8_t *tail;
    size_t tail_size = 0;

    // AVB structs
    AvbFooter *avb_footer;
    AvbVBMetaImageHeader *avb_meta;

    // Pointers to blocks defined in header
    uint8_t *hdr_addr;
    uint8_t *kernel;
    uint8_t *ramdisk;
    uint8_t *second;
    uint8_t *extra;
    uint8_t *recovery_dtbo;
    uint8_t *dtb;

    boot_img(const char *);
    ~boot_img();

    void parse_image(uint8_t *addr, format_t type);
};
