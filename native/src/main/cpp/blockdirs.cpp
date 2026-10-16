#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <unistd.h>
#include <dirent.h>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <ctime>
#include <android/log.h>
#include <sys/stat.h>
#include <errno.h>
#include <sys/types.h>
#include <time.h>

#include "shadowhook.h"

#define TAG "BlockDirsNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ============ 原始函数指针（由 shadowhook 回填） ============
static int (*orig_mkdir)(const char *, mode_t) = nullptr;
static int (*orig_mkdirat)(int, const char *, mode_t) = nullptr;

// ============ 要删除的目录名名单（由 Java 层写入） ============
static std::vector<std::string> g_delete_names;
static std::mutex g_names_mutex;

// ============ 已处理过的卷根，避免同一挂载被反复删除 ============
static std::vector<std::pair<std::string, time_t> > g_recent_vols;
static std::mutex g_recent_mutex;

// 同一卷根在 10 秒内只处理一次，防止 mkdir 高频调用导致重复删除
static bool should_process_volume(const std::string &volRoot) {
    std::lock_guard<std::mutex> lock(g_recent_mutex);
    time_t now = time(nullptr);
    for (std::vector<std::pair<std::string, time_t> >::iterator it = g_recent_vols.begin();
         it != g_recent_vols.end(); ) {
        if (now - it->second > 10) {
            it = g_recent_vols.erase(it);
        } else {
            if (it->first == volRoot) {
                return false;
            }
            ++it;
        }
    }
    g_recent_vols.push_back(std::make_pair(volRoot, now));
    return true;
}

// ============ 后台删除线程 ============
//
// 为什么需要它：
//   删除的触发点必须落在「卷已挂载就绪」之后的低频事件上。
//   实测发现两条原触发通道都不可靠：
//     1) Java 层 StorageManager.registerListener 需要 Application Context，
//        而 MediaProvider 进程里 ActivityThread.currentApplication() 返回 null，
//        监听器根本没注册成功（LSPosed 日志明确报 "no application context"）。
//     2) nativeSetDeleteNames 里立即扫一次，但 provider 刚启动时卷常常还没
//        挂载就绪，collect_volume_roots 拿到的卷根 lstat 失败被跳过，
//        此后没有任何后续触发。
//   而在「卷挂载后就绪」那一刻，系统几乎必然会对卷根执行 mkdir（重建媒体
//   目录），native 的 my_mkdir 命中一次即证明「卷此刻可用」。
//   因此：my_mkdir 命中时只置一个「待处理卷」标记并唤醒本线程（热路径近乎
//   零开销：一次加锁 + 一次赋值 + signal），实际扫描/递归删除全部放到本
//   后台线程里做，既避开热路径开销，又天然由真实卷活动驱动（无需任何轮询）。
static std::mutex g_pending_mutex;
static std::condition_variable g_pending_cv;
static std::string g_pending_vol;      // 待处理的卷根（空=无）

// 前置声明
static void scan_all_volumes();

// 唤醒后台删除线程：由 mkdir 命中 / 名单更新等低频事件调用。
// 极轻量，可在热路径安全调用。
static void notify_volume_activity(const std::string &volRoot) {
    {
        std::lock_guard<std::mutex> lock(g_pending_mutex);
        g_pending_vol = volRoot;
    }
    g_pending_cv.notify_one();
}

// 后台线程主循环：等待唤醒 -> 扫描全部外置卷并删除勾选目录。
static void delete_worker_loop() {
    for (;;) {
        bool has_work = false;
        {
            std::unique_lock<std::mutex> lock(g_pending_mutex);
            g_pending_cv.wait(lock, [] { return !g_pending_vol.empty(); });
            g_pending_vol.clear();
            has_work = true;
        }
        if (has_work) {
            // 稍等一下，避开「卷刚出现、根目录权限/挂载尚未完全就绪」的瞬间；
            // 也给系统重建目录的动作留出时间，让一次性扫描能覆盖到。
            usleep(300 * 1000);
            scan_all_volumes();
        }
    }
}

// 懒启动后台线程（只起一个，进程内单例）。
static void ensure_delete_worker() {
    static std::once_flag once;
    std::call_once(once, [] {
        std::thread t(delete_worker_loop);
        t.detach();
        LOGI("delete worker thread started uid=%d pid=%d", (int)getuid(), (int)getpid());
    });
}

// 删除一个目录，失败于 ENOTEMPTY/EBUSY 时重试若干次（每次间隔递增）。
//
// 为什么必须重试（真机实测结论）：
//   外置卷（exfat/vfat）经 FUSE 挂载。删除其上的目录时，VFS 的 dentry/attr
//   缓存存在滞后：当刚刚递归删除了某个目录的所有子项后「立刻」rmdir 该目录，
//   内核可能仍基于过期缓存返回 ENOTEMPTY（Directory not empty），
//   尤其当目录正被其它 app（如微信）活跃访问时更明显。
//   实测：删除 Android/data/<app>/cache 后立刻 rmdir <app> 会 ENOTEMPTY；
//         间隔约 2 秒后重试即成功。因此这里加入短等待重试。
static bool rmdir_with_retry(const std::string &path) {
    const int kMaxTries = 8;
    for (int i = 0; i < kMaxTries; ++i) {
        if (rmdir(path.c_str()) == 0) {
            return true;
        }
        int e = errno;
        // 只对「目录非空 / 忙」这类可恢复错误重试；
        // ENOENT 说明已被删除（成功）；其它错误（权限等）不再白费力气。
        if (e == ENOENT) {
            return true;
        }
        if (e != ENOTEMPTY && e != EBUSY && e != EEXIST) {
            return false;
        }
        usleep(150 * 1000);  // 150ms，等 FUSE 缓存刷新
    }
    return rmdir(path.c_str()) == 0;
}

// 递归删除目录/文件（删除用 unlink/rmdir，不经过被 hook 的 mkdir）
static bool remove_recursive(const std::string &path) {
    struct stat st;
    if (lstat(path.c_str(), &st) != 0) {
        return errno == ENOENT;  // 已不存在视为成功
    }
    if (S_ISDIR(st.st_mode)) {
        // 先递归删除所有子项。若子项删除不完全（活跃引用），
        // 下面的 rmdir_with_retry 有可能在稍后成功清空。
        for (int round = 0; round < 3; ++round) {
            DIR *d = opendir(path.c_str());
            if (d == nullptr) {
                break;
            }
            bool had_child = false;
            struct dirent *ent;
            while ((ent = readdir(d)) != nullptr) {
                if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) {
                    continue;
                }
                had_child = true;
                remove_recursive(path + "/" + ent->d_name);
            }
            closedir(d);
            // 子项已清空则跳出，交给 rmdir_with_retry 删本目录。
            DIR *chk = opendir(path.c_str());
            if (chk == nullptr) {
                break;
            }
            bool still_has = false;
            while ((ent = readdir(chk)) != nullptr) {
                if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) {
                    continue;
                }
                still_has = true;
                break;
            }
            closedir(chk);
            if (!still_has) {
                break;
            }
            if (!had_child && still_has) {
                // 有子项但删不掉（被占用/重建），再试也没用，避免死循环。
                break;
            }
            usleep(150 * 1000);
        }
        return rmdir_with_retry(path);
    } else {
        if (unlink(path.c_str()) == 0) {
            return true;
        }
        return errno == ENOENT;
    }
}

// 判断路径是否是「外置卷根目录下的第一层目录」，并提取卷根与第一层名
// /storage/XXXX-XXXX/name  -> volRoot = /storage/XXXX-XXXX, firstName = name
static bool extract_vol_and_name(const char *path, std::string &volRoot, std::string &firstName) {
    if (path == nullptr) {
        return false;
    }

    const char *base = nullptr;
    if (strncmp(path, "/storage/", 9) == 0) {
        base = path + 9;
    } else if (strncmp(path, "/mnt/media_rw/", 14) == 0) {
        base = path + 14;
    } else if (strncmp(path, "/mnt/secure/", 12) == 0) {
        base = path + 12;
    } else {
        return false;
    }

    // 排除内部共享存储 /storage/emulated
    if (strncmp(base, "emulated", 8) == 0) {
        return false;
    }

    const char *slash = strchr(base, '/');
    if (slash == nullptr) {
        return false;
    }

    const char *name = slash + 1;
    if (*name == '\0') {
        return false;
    }

    // 更深层不处理
    if (strchr(name, '/') != nullptr) {
        return false;
    }

    size_t prefixLen = (size_t)(slash - path);
    volRoot.assign(path, prefixLen);
    firstName.assign(name);
    return true;
}

// 判断路径是否是「外置卷根目录下的第一层目录」（用于拦截创建）
static bool is_top_level_on_external(const char *path) {
    std::string volRoot, firstName;
    return extract_vol_and_name(path, volRoot, firstName);
}

// 对某个卷根执行「删除用户勾选的目录」
static void do_delete_on_volume(const std::string &volRoot) {
    std::vector<std::string> names;
    {
        std::lock_guard<std::mutex> lock(g_names_mutex);
        names = g_delete_names;
    }
    LOGI("delete_on_volume %s uid=%d names=%zu", volRoot.c_str(), (int)getuid(), names.size());
    if (names.empty()) {
        return;
    }
    for (size_t i = 0; i < names.size(); ++i) {
        const std::string &n = names[i];
        std::string target = volRoot + "/" + n;
        struct stat st;
        if (lstat(target.c_str(), &st) != 0) {
            continue;  // 目录本就不存在，无需处理（正常情况，不打日志）
        }
        // 目标若在首次删除后仍残留（典型的：子项刚删净但父目录因 FUSE
        // 缓存滞后未能一次删掉），允许整体再尝试若干次。
        bool ok = false;
        for (int attempt = 0; attempt < 3; ++attempt) {
            if (remove_recursive(target)) {
                ok = true;
                break;
            }
            if (lstat(target.c_str(), &st) != 0) {
                ok = true;  // 已被删除
                break;
            }
            sleep(1);  // 给 FUSE/占用方 1 秒，再整体重试
        }
        if (ok) {
            LOGI("DELETED %s", target.c_str());
        } else {
            LOGW("delete failed: %s (errno=%d %s)", target.c_str(), errno, strerror(errno));
        }
    }
}

// 从 /proc/self/mounts 中解析出所有外置卷挂载点。
//
// 为什么要用 /proc/self/mounts 而不是 opendir("/storage")：
//   MediaProvider 的 uid(10192) 对 /storage 只有 group 的 x(可进入)权限，
//   没有 r(读)权限（/storage 权限位是 0710，owner=root group=media_rw）。
//   因此 opendir("/storage") 会 EACCES(errno=13)，无法枚举卷。
//   但「已知卷根路径的访问」只需要 x 权限即可 —— 所以只要能拿到卷根路径，
//   就能正常 lstat / 进入 / 删除。
//   /proc/self/mounts 是内核提供的、任何进程都可读的挂载表，
//   其中包含 /storage/<uuid> 这类挂载点，正好可以作为卷根来源。
static void collect_volume_roots(std::vector<std::string> &out) {
    FILE *fp = fopen("/proc/self/mounts", "r");
    if (fp == nullptr) {
        LOGE("mounts fopen FAIL errno=%d(%s)", errno, strerror(errno));
        return;
    }
    char line[4096];
    while (fgets(line, sizeof(line), fp) != nullptr) {
        // 字段: dev mountpoint fstype options dump pass
        char dev[512], mp[1024], fstype[128];
        if (sscanf(line, "%511s %1023s %127s", dev, mp, fstype) != 3) {
            continue;
        }
        // 只关心外置卷挂载点：/storage/<uuid>
        if (strncmp(mp, "/storage/", 9) != 0) {
            continue;
        }
        const char *base = mp + 9;
        // 排除内部共享存储及其它伪目录
        if (strncmp(base, "emulated", 8) == 0) continue;
        if (strncmp(base, "self", 4) == 0) continue;
        if (strncmp(base, "persist", 7) == 0) continue;
        if (base[0] == '\0') continue;
        // 卷根只允许 /storage/<uuid>（后面不再有 '/'）
        if (strchr(base, '/') != nullptr) continue;
        out.push_back(std::string(mp));
        LOGI("volume root=%s fstype=%s", mp, fstype);
    }
    fclose(fp);
}

// 扫描所有外置卷的根目录，对每个卷执行一次删除。
// 不依赖 mkdir 是否被调用，因此即使勾选目录已存在也能删。
static void scan_all_volumes() {
    LOGI("scan_all_volumes uid=%d", (int)getuid());
    std::vector<std::string> roots;
    collect_volume_roots(roots);
    for (size_t i = 0; i < roots.size(); ++i) {
        const std::string &volRoot = roots[i];
        struct stat st;
        if (lstat(volRoot.c_str(), &st) != 0) {
            continue;
        }
        if (!S_ISDIR(st.st_mode)) {
            continue;
        }
        // 同一个卷可能同时出现在 /storage 与 /mnt/media_rw（bind mount），
        // 用时间窗去重，避免对同一物理卷重复遍历。
        if (!should_process_volume(volRoot)) {
            continue;
        }
        do_delete_on_volume(volRoot);
    }
}

// ============ Hook 后的 mkdir / mkdirat ============
//
// 注意：必须调用 SHADOWHOOK_STACK_SCOPE()（C++ RAII，函数退出时自动调用
// shadowhook_pop_stack）。否则 shadowhook 的 hub 会认为上一次代理调用“还在递归中”，
// 从第二次调用开始就不再调用本代理函数，导致 hook 形同虚设。
// 调用原函数必须用 SHADOWHOOK_CALL_PREV 宏（官方要求），不能直接调裸函数指针。
// 说明：这两个代理函数位于 mkdir 热路径上，必须保持极轻量。
// 这里只做「判断 + 拦截」，不做任何删除/扫描（删除改由 Java 层在
// provider 启动、卷挂载状态变化这类低频事件里主动触发）。
// 热路径上不得有 LOG 打印（进程频繁 mkdir 时日志本身即为可观开销）。
static int my_mkdir(const char *path, mode_t mode) {
    SHADOWHOOK_STACK_SCOPE();

    if (is_top_level_on_external(path)) {
        LOGW("BLOCKED mkdir (top-level on external volume): %s", path);
        // 命中即证明「该外置卷此刻已挂载就绪」（系统正在往上写目录）。
        // 这里只做一次超轻量的标记 + 唤醒后台删除线程，绝不在热路径上做
        // 扫描/递归/删除。真正的删除由后台线程执行。
        std::string volRoot, firstName;
        if (extract_vol_and_name(path, volRoot, firstName)) {
            ensure_delete_worker();
            notify_volume_activity(volRoot);
        }
        return 0;  // 假装成功，但不真正创建
    }
    return SHADOWHOOK_CALL_PREV(my_mkdir, path, mode);
}

static int my_mkdirat(int dirfd, const char *path, mode_t mode) {
    SHADOWHOOK_STACK_SCOPE();

    if (is_top_level_on_external(path)) {
        LOGW("BLOCKED mkdirat (top-level on external volume): %s", path);
        std::string volRoot, firstName;
        if (extract_vol_and_name(path, volRoot, firstName)) {
            ensure_delete_worker();
            notify_volume_activity(volRoot);
        }
        return 0;
    }
    return SHADOWHOOK_CALL_PREV(my_mkdirat, dirfd, path, mode);
}

extern "C" JNIEXPORT void JNICALL
Java_top_yixiangren_blockdirs_MainHook_installNativeHook(JNIEnv *env, jclass clazz) {
    LOGI("installNativeHook uid=%d pid=%d", (int)getuid(), (int)getpid());

    int sh_init = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
    if (sh_init != 0) {
        LOGE("shadowhook_init FAILED ret=%d", sh_init);
        return;
    }

    void *mkdir_stub = shadowhook_hook_sym_name(
            "libc.so", "mkdir", (void *)my_mkdir, (void **)&orig_mkdir);
    if (mkdir_stub == nullptr) {
        LOGE("mkdir hook FAILED, errno=%d(%s)", errno, strerror(errno));
    }

    void *mkdirat_stub = shadowhook_hook_sym_name(
            "libc.so", "mkdirat", (void *)my_mkdirat, (void **)&orig_mkdirat);
    if (mkdirat_stub == nullptr) {
        LOGE("mkdirat hook FAILED, errno=%d(%s)", errno, strerror(errno));
    }

    LOGI("hook installed: mkdir=%p mkdirat=%p", mkdir_stub, mkdirat_stub);

    // 确保后台删除线程已启动（幂等）。即使此时名单尚空，线程也只是等待。
    ensure_delete_worker();
}

// ============ 供 Java 层调用的接口 ============

// 设置「要删除的目录名名单」。传空数组表示关闭自动删除。
extern "C" JNIEXPORT void JNICALL
Java_top_yixiangren_blockdirs_MainHook_nativeSetDeleteNames(JNIEnv *env, jclass clazz,
                                                            jobjectArray names) {
    std::vector<std::string> tmp;
    if (names != nullptr) {
        jsize len = env->GetArrayLength(names);
        for (jsize i = 0; i < len; ++i) {
            jstring js = (jstring) env->GetObjectArrayElement(names, i);
            if (js == nullptr) {
                continue;
            }
            const char *c = env->GetStringUTFChars(js, nullptr);
            if (c != nullptr) {
                tmp.push_back(std::string(c));
                env->ReleaseStringUTFChars(js, c);
            }
            env->DeleteLocalRef(js);
        }
    }
    {
        std::lock_guard<std::mutex> lock(g_names_mutex);
        g_delete_names = tmp;
    }
    LOGI("setDeleteNames count=%zu uid=%d", tmp.size(), (int)getuid());

    // 名单一旦更新（且非空），启动后台删除线程并立即唤醒一次，
    // 尝试对当前已就绪的卷删除。注意：provider 刚启动时卷可能尚未挂载
    // 就绪，此时这次扫描可能空跑 —— 没关系，后续任一 mkdir 命中（卷真正
    // 就绪的信号）都会再次唤醒本线程，保证最终一定删到。
    if (!tmp.empty()) {
        ensure_delete_worker();
        notify_volume_activity("__all__");  // 空标记表示「先全量扫一次」
    }
}

// 主动对某个卷根执行一次删除（供 Java 层在检测到挂载时调用）
extern "C" JNIEXPORT void JNICALL
Java_top_yixiangren_blockdirs_MainHook_nativeDeleteOnVolume(JNIEnv *env, jclass clazz,
                                                            jstring volumeRoot) {
    if (volumeRoot == nullptr) {
        return;
    }
    const char *c = env->GetStringUTFChars(volumeRoot, nullptr);
    if (c == nullptr) {
        return;
    }
    std::string volRoot(c);
    env->ReleaseStringUTFChars(volumeRoot, c);

    while (!volRoot.empty() && volRoot[volRoot.size() - 1] == '/') {
        volRoot.erase(volRoot.size() - 1);
    }
    if (volRoot.empty()) {
        return;
    }
    LOGI("nativeDeleteOnVolume requested: %s", volRoot.c_str());
    do_delete_on_volume(volRoot);
}