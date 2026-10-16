#include <jni.h>
#include <string>
#include <cstring>
#include <cstdlib>
#include <dlfcn.h>
#include <unistd.h>
#include <android/log.h>
#include <sys/stat.h>

#include "shadowhook.h"

#define TAG "BlockDirsNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 原始函数指针（由 shadowhook 回填）
static int (*orig_mkdir)(const char *, mode_t) = nullptr;
static int (*orig_mkdirat)(int, const char *, mode_t) = nullptr;

// 判断路径是否是「外置卷根目录下的第一层目录」
// 即 /storage/XXXX-XXXX/<name> 或 /mnt/media_rw/XXXX/<name>
static bool is_top_level_on_external(const char *path) {
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

    // 找到卷 UUID 后的第一个 '/'，后面是第一层目录名
    const char *slash = strchr(base, '/');
    if (slash == nullptr) {
        return false;
    }

    const char *name = slash + 1;
    if (*name == '\0') {
        return false;
    }

    // 更深层不拦
    if (strchr(name, '/') != nullptr) {
        return false;
    }

    return true;
}

// Hook 后的 mkdir
static int my_mkdir(const char *path, mode_t mode) {
    if (is_top_level_on_external(path)) {
        LOGW("BLOCKED mkdir (top-level on external volume): %s", path);
        return 0;
    }
    return orig_mkdir(path, mode);
}

// Hook 后的 mkdirat
static int my_mkdirat(int dirfd, const char *path, mode_t mode) {
    if (is_top_level_on_external(path)) {
        LOGW("BLOCKED mkdirat (top-level on external volume): %s", path);
        return 0;
    }
    return orig_mkdirat(dirfd, path, mode);
}

extern "C" JNIEXPORT void JNICALL
Java_top_yixiangren_blockdirs_MainHook_installNativeHook(JNIEnv *env, jclass clazz) {
    LOGI("installNativeHook: block ALL top-level dirs on external volumes");

    // 初始化 shadowhook（默认 inline 模式）
    if (shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false) != 0) {
        LOGE("shadowhook_init failed");
        return;
    }

    // Hook libc 的 mkdir
    void *mkdir_stub = shadowhook_hook_sym_name(
            "libc.so", "mkdir", (void *)my_mkdir, (void **)&orig_mkdir);
    if (mkdir_stub != nullptr) {
        LOGI("mkdir hooked, orig=%p", (void *)orig_mkdir);
    } else {
        LOGE("mkdir hook failed");
    }

    // Hook libc 的 mkdirat
    void *mkdirat_stub = shadowhook_hook_sym_name(
            "libc.so", "mkdirat", (void *)my_mkdirat, (void **)&orig_mkdirat);
    if (mkdirat_stub != nullptr) {
        LOGI("mkdirat hooked, orig=%p", (void *)orig_mkdirat);
    } else {
        LOGE("mkdirat hook failed");
    }
}