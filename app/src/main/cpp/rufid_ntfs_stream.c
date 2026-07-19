/*
 * Rufid NTFS stream tool - length-prefixed binary protocol on stdin.
 * Linked statically to libntfs-3g. No shell path parsing. No FUSE.
 *
 * CLI: librufidntfsstream.so <image-path>
 *
 * Protocol:
 *   u8  mode: 1=populate, 2=verify
 *   records until END:
 *     u8  kind: 0=END, 1=DIR, 2=FILE
 *     u16 path_len (LE)
 *     path_len bytes UTF-8 relative path
 *     if FILE: u64 size (LE) + size content bytes
 *
 * Exit nonzero on any validation, I/O, or content mismatch.
 */

#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <locale.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

#include "attrib.h"
#include "dir.h"
#include "inode.h"
#include "layout.h"
#include "types.h"
#include "unistr.h"
#include "volume.h"

#define RUFID_MODE_POPULATE 1
#define RUFID_MODE_VERIFY 2
#define RUFID_KIND_END 0
#define RUFID_KIND_DIR 1
#define RUFID_KIND_FILE 2
#define RUFID_MAX_PATH 4096
#define RUFID_MAX_UCS_NAME 255
#define RUFID_IO_CHUNK (256 * 1024)

static void die(const char *msg) {
    fprintf(stderr, "rufid_ntfs_stream: %s\n", msg);
    exit(1);
}

static void die_errno(const char *msg) {
    fprintf(stderr, "rufid_ntfs_stream: %s: %s\n", msg, strerror(errno));
    exit(1);
}

static void close_inode_or_die(ntfs_inode *ni, const char *what) {
    int tries = 0;
    (void)ntfs_inode_sync(ni);
    while (ntfs_inode_close(ni)) {
        if (errno == EBUSY && tries < 5) {
            tries++;
            usleep(1000 * 50);
            continue;
        }
        fprintf(stderr, "rufid_ntfs_stream: close %s failed errno=%d: %s\n",
                what, errno, strerror(errno));
        exit(1);
    }
}

static ntfs_volume *remount_after_populate_record(
        ntfs_volume *vol,
        const char *image,
        ntfs_mount_flags flags) {
    if (ntfs_umount(vol, FALSE)) {
        die_errno("ntfs_umount after populate record failed");
    }
    {
        int fd = open(image, O_RDWR);
        if (fd >= 0) {
            fsync(fd);
            close(fd);
        }
    }
    vol = ntfs_mount(image, flags);
    if (!vol) {
        die_errno("ntfs_mount after populate record failed");
    }
    if (ntfs_volume_get_free_space(vol)) {
        ntfs_umount(vol, TRUE);
        die_errno("free space after remount failed");
    }
    return vol;
}

static int read_full(void *buf, size_t len) {
    uint8_t *p = (uint8_t *)buf;
    size_t done = 0;
    while (done < len) {
        ssize_t n = read(STDIN_FILENO, p + done, len - done);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        if (n == 0) {
            return -2;
        }
        done += (size_t)n;
    }
    return 0;
}

static uint16_t read_u16_le(void) {
    uint8_t b[2];
    if (read_full(b, 2) != 0) {
        die("truncated u16");
    }
    return (uint16_t)(b[0] | ((uint16_t)b[1] << 8));
}

static uint64_t read_u64_le(void) {
    uint8_t b[8];
    if (read_full(b, 8) != 0) {
        die("truncated u64");
    }
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) {
        v |= ((uint64_t)b[i]) << (8 * i);
    }
    return v;
}

/* Reject sizes that cannot cast to s64 for ntfs_attr_pwrite. */
static void require_s64_size(uint64_t size) {
    if (size > (uint64_t)INT64_MAX) {
        die("file size exceeds INT64_MAX");
    }
}

static int is_windows_forbidden_char(unsigned char c) {
    if (c < 0x20 || c == 0x7f) {
        return 1;
    }
    switch (c) {
    case '\\':
    case '/': /* only as separator; component walk rejects if present */
    case ':':
    case '*':
    case '?':
    case '"':
    case '<':
    case '>':
    case '|':
        return 1;
    default:
        return 0;
    }
}

static int ascii_ieq(const char *a, size_t alen, const char *lit) {
    size_t i;
    for (i = 0; lit[i] != '\0'; ++i) {
        if (i >= alen) {
            return 0;
        }
        char ca = a[i];
        char cb = lit[i];
        if (ca >= 'a' && ca <= 'z') {
            ca = (char)(ca - 'a' + 'A');
        }
        if (cb >= 'a' && cb <= 'z') {
            cb = (char)(cb - 'a' + 'A');
        }
        if (ca != cb) {
            return 0;
        }
    }
    return i == alen;
}

/* DOS device names: CON/PRN/AUX/NUL/COM1-9/LPT1-9, optional extension. */
static int is_dos_device_name(const char *seg, size_t seglen) {
    size_t base_len = seglen;
    const char *dot = memchr(seg, '.', seglen);
    if (dot) {
        base_len = (size_t)(dot - seg);
    }
    if (base_len == 0) {
        return 0;
    }
    if (ascii_ieq(seg, base_len, "CON") ||
            ascii_ieq(seg, base_len, "PRN") ||
            ascii_ieq(seg, base_len, "AUX") ||
            ascii_ieq(seg, base_len, "NUL")) {
        return 1;
    }
    if (base_len == 4) {
        char c0 = seg[0];
        char c1 = seg[1];
        char c2 = seg[2];
        char c3 = seg[3];
        if (c0 >= 'a' && c0 <= 'z') {
            c0 = (char)(c0 - 'a' + 'A');
        }
        if (c1 >= 'a' && c1 <= 'z') {
            c1 = (char)(c1 - 'a' + 'A');
        }
        if (c2 >= 'a' && c2 <= 'z') {
            c2 = (char)(c2 - 'a' + 'A');
        }
        if ((c0 == 'C' && c1 == 'O' && c2 == 'M' && c3 >= '1' && c3 <= '9') ||
                (c0 == 'L' && c1 == 'P' && c2 == 'T' && c3 >= '1' && c3 <= '9')) {
            return 1;
        }
    }
    return 0;
}

static int path_is_safe(const char *path, size_t len) {
    if (len == 0 || len > RUFID_MAX_PATH) {
        return 0;
    }
    if (path[0] == '/') {
        return 0;
    }
    for (size_t i = 0; i < len; ++i) {
        unsigned char c = (unsigned char)path[i];
        if (c == 0) {
            return 0;
        }
        if (c == '/') {
            continue; /* separator */
        }
        if (is_windows_forbidden_char(c)) {
            return 0;
        }
    }
    size_t start = 0;
    for (size_t i = 0; i <= len; ++i) {
        if (i == len || path[i] == '/') {
            size_t seglen = i - start;
            if (seglen == 0) {
                return 0;
            }
            if (seglen == 1 && path[start] == '.') {
                return 0;
            }
            if (seglen == 2 && path[start] == '.' && path[start + 1] == '.') {
                return 0;
            }
            /* Trailing dot or space (Windows-forbidden). */
            char last = path[start + seglen - 1];
            if (last == '.' || last == ' ') {
                return 0;
            }
            if (is_dos_device_name(path + start, seglen)) {
                return 0;
            }
            start = i + 1;
        }
    }
    return 1;
}

static ntfs_inode *open_root(ntfs_volume *vol) {
    /* Match upstream ntfscp: FILE_root, not pathname ".". */
    ntfs_inode *root = ntfs_inode_open(vol, FILE_root);
    if (!root) {
        die_errno("ntfs_inode_open FILE_root failed");
    }
    return root;
}

static ntfschar *name_to_ucs_or_die(const char *seg, int *out_len) {
    ntfschar *uname = NULL;
    int uname_len = ntfs_mbstoucs(seg, &uname);
    if (uname_len < 0) {
        die("utf-8 to unicode failed for name");
    }
    if (uname_len == 0 || uname_len > RUFID_MAX_UCS_NAME) {
        free(uname);
        die("Unicode name length must be 1..255 ntfschar units");
    }
    *out_len = uname_len;
    return uname;
}

static ntfs_inode *ensure_dir(ntfs_volume *vol, const char *relpath) {
    if (relpath[0] == '\0') {
        return open_root(vol);
    }
    ntfs_inode *existing = ntfs_pathname_to_inode(vol, NULL, relpath);
    if (existing) {
        if (!(existing->mrec->flags & MFT_RECORD_IS_DIRECTORY)) {
            ntfs_inode_close(existing);
            die("path exists but is not a directory");
        }
        return existing;
    }

    char buf[RUFID_MAX_PATH + 1];
    size_t len = strlen(relpath);
    if (len > RUFID_MAX_PATH) {
        die("path too long");
    }
    memcpy(buf, relpath, len);
    buf[len] = '\0';

    ntfs_inode *parent = open_root(vol);

    char *save = buf;
    char *seg;
    char built[RUFID_MAX_PATH + 1];
    built[0] = '\0';
    while ((seg = strsep(&save, "/")) != NULL) {
        if (seg[0] == '\0') {
            continue;
        }
        if (built[0] != '\0') {
            size_t bl = strlen(built);
            if (bl + 1 + strlen(seg) > RUFID_MAX_PATH) {
                ntfs_inode_close(parent);
                die("path too long");
            }
            built[bl] = '/';
            memcpy(built + bl + 1, seg, strlen(seg) + 1);
        } else {
            memcpy(built, seg, strlen(seg) + 1);
        }

        ntfs_inode *child = ntfs_pathname_to_inode(vol, NULL, built);
        if (child) {
            if (!(child->mrec->flags & MFT_RECORD_IS_DIRECTORY)) {
                ntfs_inode_close(child);
                ntfs_inode_close(parent);
                die("path component is not a directory");
            }
            ntfs_inode_close(parent);
            parent = child;
            continue;
        }

        int uname_len = 0;
        ntfschar *uname = name_to_ucs_or_die(seg, &uname_len);
        child = ntfs_create(parent, const_cpu_to_le32(0), uname, (u8)uname_len, S_IFDIR);
        free(uname);
        if (!child) {
            ntfs_inode_close(parent);
            die("ntfs_create directory failed");
        }
        ntfs_inode_close(parent);
        parent = child;
    }
    return parent;
}

static void populate_dir(ntfs_volume *vol, const char *path) {
    ntfs_inode *ni = ensure_dir(vol, path);
    if (!ni) {
        die("ensure_dir failed");
    }
    close_inode_or_die(ni, "directory");
}

static void write_file_data(ntfs_inode *ni, uint64_t size) {
    require_s64_size(size);
    ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
    if (!na) {
        ntfs_inode_close(ni);
        die("open $DATA failed");
    }
    uint8_t *chunk = (uint8_t *)malloc(RUFID_IO_CHUNK);
    if (!chunk) {
        ntfs_attr_close(na);
        ntfs_inode_close(ni);
        die("oom");
    }
    uint64_t pos = 0;
    while (pos < size) {
        size_t want = (size_t)((size - pos) > RUFID_IO_CHUNK ? RUFID_IO_CHUNK : (size - pos));
        if (read_full(chunk, want) != 0) {
            free(chunk);
            ntfs_attr_close(na);
            ntfs_inode_close(ni);
            die("truncated file content");
        }
        s64 written = ntfs_attr_pwrite(na, (s64)pos, (s64)want, chunk);
        if (written != (s64)want) {
            free(chunk);
            ntfs_attr_close(na);
            ntfs_inode_close(ni);
            die("ntfs_attr_pwrite short write");
        }
        pos += want;
    }
    free(chunk);
    if ((na->data_flags & ATTR_COMPRESSION_MASK) && ntfs_attr_pclose(na)) {
        ntfs_attr_close(na);
        ntfs_inode_close(ni);
        die("ntfs_attr_pclose failed");
    }
    ntfs_attr_close(na);
    close_inode_or_die(ni, "file");
}

static void populate_file(ntfs_volume *vol, const char *path, uint64_t size) {
    require_s64_size(size);

    char parent_path[RUFID_MAX_PATH + 1];
    const char *slash = strrchr(path, '/');
    const char *base;
    ntfs_inode *parent;
    if (slash) {
        size_t plen = (size_t)(slash - path);
        if (plen > RUFID_MAX_PATH) {
            die("parent path too long");
        }
        memcpy(parent_path, path, plen);
        parent_path[plen] = '\0';
        base = slash + 1;
        parent = ensure_dir(vol, parent_path);
    } else {
        base = path;
        parent = open_root(vol);
    }
    if (!parent) {
        die("parent dir failed");
    }
    if (base[0] == '\0') {
        ntfs_inode_close(parent);
        die("empty file name");
    }

    ntfs_inode *existing = ntfs_pathname_to_inode(vol, NULL, path);
    if (existing) {
        ntfs_inode_close(existing);
        ntfs_inode_close(parent);
        die("file already exists");
    }

    int uname_len = 0;
    ntfschar *uname = name_to_ucs_or_die(base, &uname_len);
    ntfs_inode *ni = ntfs_create(parent, const_cpu_to_le32(0), uname, (u8)uname_len, S_IFREG);
    free(uname);
    if (!ni) {
        ntfs_inode_close(parent);
        die("ntfs_create file failed");
    }
    if (ntfs_inode_sync(parent)) {
        ntfs_inode_close(ni);
        ntfs_inode_close(parent);
        die_errno("sync parent dir after create failed");
    }
    close_inode_or_die(parent, "parent-dir");
    write_file_data(ni, size);
    if (ntfs_volume_get_free_space(vol)) {
        die_errno("refresh free space after write failed");
    }
}

static void verify_dir(ntfs_volume *vol, const char *path) {
    ntfs_inode *ni = ntfs_pathname_to_inode(vol, NULL, path);
    if (!ni) {
        fprintf(stderr, "rufid_ntfs_stream: verify: directory missing: %s\n", path);
        exit(1);
    }
    if (!(ni->mrec->flags & MFT_RECORD_IS_DIRECTORY)) {
        ntfs_inode_close(ni);
        die("verify: path is not a directory");
    }
    ntfs_inode_close(ni);
}

static void verify_file(ntfs_volume *vol, const char *path, uint64_t size) {
    require_s64_size(size);
    ntfs_inode *ni = ntfs_pathname_to_inode(vol, NULL, path);
    if (!ni) {
        fprintf(stderr, "rufid_ntfs_stream: verify: file missing: %s\n", path);
        exit(1);
    }
    if (ni->mrec->flags & MFT_RECORD_IS_DIRECTORY) {
        ntfs_inode_close(ni);
        die("verify: path is a directory, expected regular file");
    }
    ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
    if (!na) {
        ntfs_inode_close(ni);
        die("verify: open $DATA failed");
    }
    if ((uint64_t)na->data_size != size) {
        fprintf(stderr, "rufid_ntfs_stream: size mismatch path=%s expected=%llu actual=%lld\n",
                path, (unsigned long long)size, (long long)na->data_size);
        ntfs_attr_close(na);
        ntfs_inode_close(ni);
        exit(1);
    }
    uint8_t *expected = (uint8_t *)malloc(RUFID_IO_CHUNK);
    uint8_t *actual = (uint8_t *)malloc(RUFID_IO_CHUNK);
    if (!expected || !actual) {
        free(expected);
        free(actual);
        ntfs_attr_close(na);
        ntfs_inode_close(ni);
        die("oom");
    }
    uint64_t pos = 0;
    while (pos < size) {
        size_t want = (size_t)((size - pos) > RUFID_IO_CHUNK ? RUFID_IO_CHUNK : (size - pos));
        if (read_full(expected, want) != 0) {
            free(expected);
            free(actual);
            ntfs_attr_close(na);
            ntfs_inode_close(ni);
            die("truncated expected content");
        }
        s64 got = ntfs_attr_pread(na, (s64)pos, (s64)want, actual);
        if (got != (s64)want) {
            free(expected);
            free(actual);
            ntfs_attr_close(na);
            ntfs_inode_close(ni);
            die("verify: short read from NTFS");
        }
        if (memcmp(expected, actual, want) != 0) {
            free(expected);
            free(actual);
            ntfs_attr_close(na);
            ntfs_inode_close(ni);
            die("verify: content byte mismatch");
        }
        pos += want;
    }
    free(expected);
    free(actual);
    ntfs_attr_close(na);
    ntfs_inode_close(ni);
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s <ntfs-image-path>\n", argv[0]);
        return 2;
    }
    if (setlocale(LC_ALL, "C.UTF-8") == NULL) {
        setlocale(LC_ALL, "en_US.UTF-8");
    }
    const char *image = argv[1];
    if (!image || image[0] == '\0') {
        die("empty image path");
    }

    uint8_t mode;
    if (read_full(&mode, 1) != 0) {
        die("truncated mode byte");
    }
    if (mode != RUFID_MODE_POPULATE && mode != RUFID_MODE_VERIFY) {
        die("invalid mode");
    }

    ntfs_mount_flags flags = (mode == RUFID_MODE_VERIFY) ? NTFS_MNT_RDONLY : 0;
    ntfs_volume *vol = ntfs_mount(image, flags);
    if (!vol) {
        die_errno("ntfs_mount failed");
    }
    if (mode == RUFID_MODE_POPULATE) {
        if (ntfs_volume_get_free_space(vol)) {
            ntfs_umount(vol, TRUE);
            die_errno("ntfs_volume_get_free_space failed");
        }
    }

    for (;;) {
        uint8_t kind;
        int rr = read_full(&kind, 1);
        if (rr == -2) {
            ntfs_umount(vol, TRUE);
            die("truncated record kind");
        }
        if (rr != 0) {
            ntfs_umount(vol, TRUE);
            die_errno("read kind");
        }
        if (kind == RUFID_KIND_END) {
            break;
        }
        if (kind != RUFID_KIND_DIR && kind != RUFID_KIND_FILE) {
            ntfs_umount(vol, TRUE);
            die("invalid record kind");
        }
        uint16_t path_len = read_u16_le();
        if (path_len == 0 || path_len > RUFID_MAX_PATH) {
            ntfs_umount(vol, TRUE);
            die("invalid path length");
        }
        char path[RUFID_MAX_PATH + 1];
        if (read_full(path, path_len) != 0) {
            ntfs_umount(vol, TRUE);
            die("truncated path");
        }
        path[path_len] = '\0';
        if (!path_is_safe(path, path_len)) {
            ntfs_umount(vol, TRUE);
            die("unsafe path rejected");
        }

        if (kind == RUFID_KIND_DIR) {
            if (mode == RUFID_MODE_POPULATE) {
                populate_dir(vol, path);
                /*
                 * libntfs-3g can lose a prior sibling from the parent's live
                 * index when that parent is reopened for another create in
                 * one mount. Remount each record so later lookups cannot
                 * recreate a directory under the same NTFS name.
                 */
                vol = remount_after_populate_record(vol, image, flags);
            } else {
                verify_dir(vol, path);
            }
        } else {
            uint64_t size = read_u64_le();
            if (mode == RUFID_MODE_POPULATE) {
                populate_file(vol, path, size);
                vol = remount_after_populate_record(vol, image, flags);
            } else {
                verify_file(vol, path, size);
            }
        }
    }

    if (ntfs_umount(vol, FALSE)) {
        die_errno("ntfs_umount failed");
    }
    {
        int fd = open(image, O_RDWR);
        if (fd >= 0) {
            fsync(fd);
            close(fd);
        }
        sync();
    }
    return 0;
}
