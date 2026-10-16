package top.yixiangren.blockdirs;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 阻止 MediaProvider 在 OTG/SD 卡等外置存储卷的根目录下创建任何目录。
 *
 * 实测结论（ColorOS 16 / Android 16）：
 *  目录创建者是 com.android.providers.media.module（uid 10176），
 *  创建发生在 native 层（libfuse 的 fuse_fs_mkdir -> libc 的 mkdir/mkdirat），
 *  Java 层 File.mkdir 不会被调用。
 *
 * 方案：Hook 目标进程内 libc 的 mkdir/mkdirat，
 * 只要路径是「外置卷根目录下的第一层目录」，一律返回成功但不创建。
 * 不依赖任何硬编码目录名，无遗漏。
 */
public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "BlockDirs";
    private static final String SELF_PACKAGE = "top.yixiangren.blockdirs";
    private static final String MEDIA_PROVIDER = "com.android.providers.media.module";
    private static final String MEDIA_PROVIDER_LEGACY = "com.android.providers.media";

    private static volatile boolean nativeLoaded = false;

    @Override
    public void initZygote(StartupParam startupParam) {
        // 不在 zygote 阶段加载 native 库，避免每个 fork 出来的进程都带库、
        // 每条日志都刷屏。改为在目标进程（MediaProvider）里按需加载。
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (SELF_PACKAGE.equals(lpparam.packageName)) {
            hookSelfActivation(lpparam.classLoader);
            return;
        }

        if (!isMediaProvider(lpparam.packageName)) {
            return;
        }

        if (!ensureNativeLoaded()) {
            return;
        }

        installNativeHook();
    }

    /**
     * 激活检测：LSPosed 传统模式会无条件把模块加载到自身进程（无需写进 xposed_scope）。
     * 这里 hook 自己的 {@link ModuleActive#isActive()} 使其返回 true，UI 据此显示"已激活"；
     * 关闭模块后该 hook 不再注入，自然回到 false，无任何持久化残留。
     */
    private static void hookSelfActivation(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    SELF_PACKAGE + ".ModuleActive",
                    classLoader,
                    "isActive",
                    XC_MethodReplacement.returnConstant(true));
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] hook ModuleActive.isActive failed: " + t);
        }
    }

    private static boolean isMediaProvider(String packageName) {
        return MEDIA_PROVIDER.equals(packageName) || MEDIA_PROVIDER_LEGACY.equals(packageName);
    }

    private static boolean ensureNativeLoaded() {
        if (nativeLoaded) {
            return true;
        }
        try {
            System.loadLibrary("blockdirs");
            nativeLoaded = true;
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] load native lib failed: " + t);
            return false;
        }
    }

    private static native void installNativeHook();
}