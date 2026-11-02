// linkfix.c —— 绕开 Android 对应用进程 link() 的封杀
//
// 背景：Android SELinux 禁止普通应用进程创建硬链接。proot 里 dpkg 写数据库要
// link(status, status-old)，必被 EACCES 拒 → apt install 永远退出码 100。
// 而 PRoot 的 --link2symlink 只是拿相对软链冒充，npm 原子写删掉临时目录后
// 会留下悬空软链，产生 ENOTEMPTY 之类的竞态。
//
// 做法：编成 liblinkfix.so 写进 guest 的 /etc/ld.so.preload，拦三组调用：
//   link/linkat   失败就退化成「复制」（内容在，inode 不同，对 dpkg 够用）
//   chown 家族    假 root 下直接报成功，别让 dpkg 因权限中断
//   stat 家族     nlink 多报一个，骗过 shadow 的引用计数判断
//
// 注意：本文件【由 guest 里的 gcc + glibc 编译】（CI 在 arm64 Ubuntu 容器里做），
// 不是用 Android NDK 编。因为 .so 最终要被 guest 的 glibc 加载，用 bionic 编出来
// 的会因 ABI 不符直接崩掉所有命令。所以这里就是一份普通的 Linux C 代码。

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

/* ============ 复制文件：link 被拒时的退路 ============ */

static int do_copy(const char *src, const char *dst) {
    int in = open(src, O_RDONLY | O_CLOEXEC);
    if (in < 0) return -1;
    struct stat st;
    if (fstat(in, &st) != 0) {
        int e = errno;
        close(in);
        errno = e;
        return -1;
    }
    int out = open(dst, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, st.st_mode & 07777);
    if (out < 0) {
        int e = errno;
        close(in);
        errno = e;
        return -1;
    }
    char buf[65536];
    int bad = 0;
    for (;;) {
        ssize_t n = read(in, buf, sizeof buf);
        if (n < 0) {
            bad = errno;
            break;
        }
        if (n == 0) break;
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(out, buf + off, (size_t)(n - off));
            if (w < 0) {
                bad = errno;
                break;
            }
            if (w == 0) {
                bad = EIO;
                break;
            }
            off += w;
        }
        if (bad) break;
    }
    close(in);
    close(out);
    if (bad) {
        errno = bad;
        return -1;
    }
    return 0;
}

/* 只有「权限被拒」这类错误才值得退化成复制；ENOENT 之类的原样交回，
   否则会把真实的「文件不存在」掩盖成复制出一份空文件 */
static int worth_copy(int e) {
    return e == EACCES || e == EPERM || e == EOPNOTSUPP || e == EXDEV || e == EMLINK;
}

/* ============ link / linkat ============ */

typedef int (*linkat_fn)(int, const char *, int, const char *, int);
typedef int (*link_fn)(const char *, const char *);

int linkat(int oldfd, const char *oldp, int newfd, const char *newp, int flags) {
    static linkat_fn real;
    if (!real) real = (linkat_fn)dlsym(RTLD_NEXT, "linkat");
    if (real && real(oldfd, oldp, newfd, newp, flags) == 0) return 0;

    int saved = errno ? errno : EACCES;
    if (!worth_copy(saved)) {
        errno = saved;
        return -1;
    }
    /* 只在双方都走 AT_FDCWD 的常见形态下兜底：跨 dirfd 的复制语义不准，
       与其猜错不如把真实错误交回去 */
    if (oldfd == AT_FDCWD && newfd == AT_FDCWD && (flags & AT_EMPTY_PATH) == 0) {
        if (do_copy(oldp, newp) == 0) return 0;
    }
    errno = saved;
    return -1;
}

int link(const char *oldp, const char *newp) {
    static link_fn real;
    if (!real) real = (link_fn)dlsym(RTLD_NEXT, "link");
    if (real && real(oldp, newp) == 0) return 0;

    int saved = errno ? errno : EACCES;
    if (!worth_copy(saved)) {
        errno = saved;
        return -1;
    }
    if (do_copy(oldp, newp) == 0) return 0;
    errno = saved;
    return -1;
}

/* ============ chown 家族：假 root 下当作成功 ============ */

typedef int (*chown_fn)(const char *, uid_t, gid_t);
typedef int (*fchownat_fn)(int, const char *, uid_t, gid_t, int);
typedef int (*fchown_fn)(int, uid_t, gid_t);

/* proot -0 里 getuid 已是 0；真机非 root 时不动，保持原语义 */
static int fake_root(void) { return getuid() == 0; }

int fchownat(int dirfd, const char *path, uid_t uid, gid_t gid, int flags) {
    static fchownat_fn real;
    if (!real) real = (fchownat_fn)dlsym(RTLD_NEXT, "fchownat");
    if (!real) {
        errno = ENOSYS;
        return -1;
    }
    int rc = real(dirfd, path, uid, gid, flags);
    if (rc == 0) return 0;
    if (fake_root() && (errno == EPERM || errno == EACCES)) return 0;
    return rc;
}

int chown(const char *path, uid_t uid, gid_t gid) {
    return fchownat(AT_FDCWD, path, uid, gid, 0);
}

int lchown(const char *path, uid_t uid, gid_t gid) {
    return fchownat(AT_FDCWD, path, uid, gid, AT_SYMLINK_NOFOLLOW);
}

int fchown(int fd, uid_t uid, gid_t gid) {
    static fchown_fn real;
    if (!real) real = (fchown_fn)dlsym(RTLD_NEXT, "fchown");
    if (!real) {
        errno = ENOSYS;
        return -1;
    }
    int rc = real(fd, uid, gid);
    if (rc == 0) return 0;
    if (fake_root() && (errno == EPERM || errno == EACCES)) return 0;
    return rc;
}

/* ============ stat 家族：nlink 多报一个 ============ */

/* shadow 用 nlink 判断锁文件是否还有别的引用；复制出来的文件 nlink 恒为 1，
   会被当成「锁仍被持有」。多报一个骗过去，代价是 rm 之类判断略偏 */
static void bump_nlink(struct stat *st) {
    if (st && st->st_nlink == 1) st->st_nlink = 2;
}

typedef int (*stat_fn)(const char *, struct stat *);
typedef int (*lstat_fn)(const char *, struct stat *);
typedef int (*fstat_fn)(int, struct stat *);
typedef int (*fstatat_fn)(int, const char *, struct stat *, int);

int stat(const char *path, struct stat *buf) {
    static stat_fn real;
    if (!real) real = (stat_fn)dlsym(RTLD_NEXT, "stat");
    int rc = real(path, buf);
    if (rc == 0) bump_nlink(buf);
    return rc;
}

int lstat(const char *path, struct stat *buf) {
    static lstat_fn real;
    if (!real) real = (lstat_fn)dlsym(RTLD_NEXT, "lstat");
    int rc = real(path, buf);
    if (rc == 0) bump_nlink(buf);
    return rc;
}

int fstat(int fd, struct stat *buf) {
    static fstat_fn real;
    if (!real) real = (fstat_fn)dlsym(RTLD_NEXT, "fstat");
    int rc = real(fd, buf);
    if (rc == 0) bump_nlink(buf);
    return rc;
}

int fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    static fstatat_fn real;
    if (!real) real = (fstatat_fn)dlsym(RTLD_NEXT, "fstatat");
    int rc = real(dirfd, path, buf, flags);
    if (rc == 0) bump_nlink(buf);
    return rc;
}