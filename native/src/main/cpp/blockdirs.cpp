#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <dlfcn.h>
#include <unistd.h>
#include <dirent.h>
#include <mutex>
#include <ctime>
#include <android/log.h>
#include <sys/stat.h>

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

// 递归删除目录/文件（删除用 unlink/rmdir，不经过被 hook 的 mkdir）
static bool remove_recursive(const std::string &path) {
    struct stat st;
    if (lstat(path.c_str(), &st) != 0) {
        return false;
    }
    if (S_ISDIR(st.st_mode)) {
        DIR *d = opendir(path.c_str());
        if (d != nullptr) {
            struct dirent *ent;
            while ((ent = readdir(d)) != nullptr) {
                if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) {
                    continue;
                }
                remove_recursive(path + "/" + ent->d_name);
            }
            closedir(d);
        }
        return rmdir(path.c_str()) == 0;
    } else {
        return unlink(path.c_str()) == 0;
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

// 前置声明：扫描全部外置卷并删除
static void scan_all_volumes();

// 对某个卷根执行「删除用户勾选的目录」
static void do_delete_on_volume(const std::string &volRoot) {
    std::vector<std::string> names;
    {
        std::lock_guard<std::mutex> lock(g_names_mutex);
        names = g_delete_names;
    }
    if (names.empty()) {
        return;
    }
    for (size_t i = 0; i < names.size(); ++i) {
        const std::string &n = names[i];
        std::string target = volRoot + "/" + n;
        struct stat st;
        if (lstat(target.c_str(), &st) == 0) {
            if (remove_recursive(target)) {
                LOGW("DELETED %s", target.c_str());
            } else {
                LOGE("delete failed: %s", target.c_str());
            }
        }
    }
}

// 对外统一入口：收到一个「可能位于卷根的路径」，若是则触发该卷的删除
static void on_volume_path_seen(const char *path) {
    std::string volRoot, firstName;
    if (!extract_vol_and_name(path, volRoot, firstName)) {
        return;
    }
    if (!should_process_volume(volRoot)) {
        return;
    }
    LOGI("volume activity detected: %s (root=%s)", path, volRoot.c_str());
    do_delete_on_volume(volRoot);
}

// 扫描 /storage 下所有外置卷的根目录，对每个卷执行一次删除。
// 不依赖 mkdir 是否被调用，因此即使勾选目录已存在也能删。
static void scan_all_volumes() {
    const char *roots[] = {"/storage", "/mnt/media_rw"};
    for (int r = 0; r < 2; ++r) {
        DIR *d = opendir(roots[r]);
        if (d == nullptr) {
            continue;
        }
        struct dirent *ent;
        while ((ent = readdir(d)) != nullptr) {
            const char *name = ent->d_name;
            if (strcmp(name, ".") == 0 || strcmp(name, "..") == 0) {
                continue;
            }
            if (strcmp(name, "emulated") == 0 || strcmp(name, "self") == 0
                    || strcmp(name, "persist") == 0) {
                continue;
            }
            std::string volRoot = std::string(roots[r]) + "/" + name;
            struct stat st;
            if (lstat(volRoot.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) {
                continue;
            }
            // 同一个卷可能同时出现在 /storage 与 /mnt/media_rw（bind mount），
            // 用时间窗去重，避免对同一物理卷重复遍历。
            if (!should_process_volume(volRoot)) {
                continue;
            }
            LOGI("SCAN volume: %s", volRoot.c_str());
            do_delete_on_volume(volRoot);
        }
        closedir(d);
    }
}

// ============ Hook 后的 mkdir / mkdirat ============
//
// 注意：必须调用 SHADOWHOOK_STACK_SCOPE()（C++ RAII，函数退出时自动调用
// shadowhook_pop_stack）。否则 shadowhook 的 hub 会认为上一次代理调用“还在递归中”，
// 从第二次调用开始就不再调用本代理函数，导致 hook 形同虚设。
// 调用原函数必须用 SHADOWHOOK_CALL_PREV 宏（官方要求），不能直接调裸函数指针。
static int my_mkdir(const char *path, mode_t mode) {
    SHADOWHOOK_STACK_SCOPE();

    if (is_top_level_on_external(path)) {
        LOGW("BLOCKED mkdir (top-level on external volume): %s", path);
        // 卷根活动 = 卷刚被挂载/初始化。顺手删除该卷已存在的勾选目录。
        on_volume_path_seen(path);
        scan_all_volumes();
        return 0;  // 假装成功，但不真正创建
    }
    return SHADOWHOOK_CALL_PREV(my_mkdir, path, mode);
}

static int my_mkdirat(int dirfd, const char *path, mode_t mode) {
    SHADOWHOOK_STACK_SCOPE();

    if (is_top_level_on_external(path)) {
        LOGW("BLOCKED mkdirat (top-level on external volume): %s", path);
        on_volume_path_seen(path);
        scan_all_volumes();
        return 0;
    }
    return SHADOWHOOK_CALL_PREV(my_mkdirat, dirfd, path, mode);
}

extern "C" JNIEXPORT void JNICALL
Java_top_yixiangren_blockdirs_MainHook_installNativeHook(JNIEnv *env, jclass clazz) {
    LOGI("installNativeHook: block ALL top-level dirs on external volumes");

    if (shadowhook_init(SHADOWHOOK_MODE_SHARED, false) != 0) {
        LOGE("shadowhook_init failed");
        return;
    }

    void *mkdir_stub = shadowhook_hook_sym_name(
            "libc.so", "mkdir", (void *)my_mkdir, (void **)&orig_mkdir);
    if (mkdir_stub != nullptr) {
        LOGI("mkdir hooked, orig=%p", (void *)orig_mkdir);
    } else {
        LOGE("mkdir hook failed");
    }

    void *mkdirat_stub = shadowhook_hook_sym_name(
            "libc.so", "mkdirat", (void *)my_mkdirat, (void **)&orig_mkdirat);
    if (mkdirat_stub != nullptr) {
        LOGI("mkdirat hooked, orig=%p", (void *)orig_mkdirat);
    } else {
        LOGE("mkdirat hook failed");
    }
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
    LOGI("nativeSetDeleteNames: %zu names", tmp.size());

    // 名单一旦更新（且非空）就立即主动扫描一次所有外置卷，
    // 不依赖后续是否发生 mkdir，解决“目标目录已存在导致不触发”的问题。
    if (!tmp.empty()) {
        scan_all_volumes();
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