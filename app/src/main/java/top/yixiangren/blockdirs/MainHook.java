package top.yixiangren.blockdirs;

import android.content.SharedPreferences;
import android.os.storage.StorageVolume;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 阻止 MediaProvider 在 OTG/SD 卡等外置存储卷的根目录下创建任何目录，
 * 并可选地「自动删除」外置卷根目录下已存在的、用户勾选的目录。
 *
 * 实测结论（ColorOS 16 / Android 16）：
 *  目录创建者是 com.android.providers.media.module（uid 10176），
 *  创建发生在 native 层（libfuse 的 fuse_fs_mkdir -> libc 的 mkdir/mkdirat），
 *  Java 层 File.mkdir 不会被调用。
 *
 * 方案：
 *  1) Hook 目标进程内 libc 的 mkdir/mkdirat：只要路径是「外置卷根目录下
 *     的第一层目录」，一律返回成功但不创建（阻止创建，默认行为）。
 *  2) 若用户开启了「自动删除」并勾选了目录，则把这些目录名传入 native，
 *     由被注入的 MediaProvider 进程在检测到卷活动时递归删除这些目录
 *     （只删第一层，递归删内容）。
 *
 * 删除触发有两条通道：
 *  A. native 层：mkdir 被拦截时（说明卷刚被写入/初始化），触发一次删除；
 *  B. Java 层：hook 存储卷挂载相关方法，主动调用 nativeDeleteOnVolume。
 *  两条通道由 native 侧 30 秒时间窗去重，不会重复删。
 */
public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "BlockDirs";
    private static final String SELF_PACKAGE = "top.yixiangren.blockdirs";
    private static final String MEDIA_PROVIDER = "com.android.providers.media.module";

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

        // 从模块自身配置文件读取用户勾选，推送给 native
        pushDeleteNames();

        // 【主动删除通道】native 加载后立即对所有已挂载外置卷执行一次删除，
        // 不依赖任何 mkdir 事件。这是「勾选 = 直接删掉」的核心保障。
        deleteOnAllVolumes();

        // 额外 hook 卷挂载检测点 + 注册存储卷监听，插卡瞬间触发删除
        hookVolumeMount(lpparam.classLoader);
        registerVolumeListener();
    }

    /**
     * 立即对所有已挂载的外置卷执行一次删除。
     *
     * 卷根来源有两条，互为兜底：
     *  1) StorageManager.getStorageVolumes()（Java 官方 API，最可靠）；
     *  2) /proc/self/mounts 中解析 /storage/&lt;uuid&gt;（不依赖任何权限）。
     *
     * 之所以必须主动做，而不是等 mkdir：用户勾选的目录可能「已存在」且系统
     * 不再重建它（例如空目录 DCIM），此时没有任何 mkdir 事件，被动触发永远不删。
     */
    private static void deleteOnAllVolumes() {
        java.util.Set<String> roots = new java.util.LinkedHashSet<>();
        collectVolumesFromStorageManager(roots);
        collectVolumesFromProcMounts(roots);
        if (roots.isEmpty()) {
            XposedBridge.log("[" + TAG + "] deleteOnAllVolumes: no external volume found");
            return;
        }
        for (String root : roots) {
            XposedBridge.log("[" + TAG + "] deleteOnAllVolumes -> " + root);
            nativeDeleteOnVolume(root);
        }
    }

    /** 通过 StorageManager 官方 API 获取所有外置卷根目录。 */
    private static void collectVolumesFromStorageManager(java.util.Set<String> out) {
        try {
            Class<?> ctxClass = Class.forName("android.app.ActivityThread");
            Object app = XposedHelpers.callStaticMethod(ctxClass, "currentApplication");
            if (app == null) {
                return;
            }
            Object sm = XposedHelpers.callMethod(app, "getSystemService", "storage");
            if (sm == null) {
                return;
            }
            java.util.List<?> volumes =
                    (java.util.List<?>) XposedHelpers.callMethod(sm, "getStorageVolumes");
            if (volumes == null) {
                return;
            }
            for (Object vol : volumes) {
                // 只处理「可移除 + 已挂载」的外置卷
                Boolean removable = null;
                try {
                    removable = (Boolean) XposedHelpers.callMethod(vol, "isRemovable");
                } catch (Throwable ignored) {
                }
                try {
                    Object state = XposedHelpers.callMethod(vol, "getState");
                    if (state != null && !"mounted".equals(state.toString())) {
                        continue;
                    }
                } catch (Throwable ignored) {
                }
                if (removable != null && !removable) {
                    continue; // 内部卷跳过
                }
                Object dir = null;
                try {
                    dir = XposedHelpers.callMethod(vol, "getDirectory");
                } catch (Throwable ignored) {
                }
                if (dir == null) {
                    try {
                        dir = XposedHelpers.callMethod(vol, "getPath");
                    } catch (Throwable ignored) {
                    }
                }
                if (dir != null) {
                    String root = toVolumeRoot(dir.toString());
                    if (root != null) {
                        out.add(root);
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] collectFromStorageManager failed: " + t);
        }
    }

    /** 从 /proc/self/mounts 解析 /storage/&lt;uuid&gt; 形式的外置卷挂载点。 */
    private static void collectVolumesFromProcMounts(java.util.Set<String> out) {
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.FileReader("/proc/self/mounts"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 3) {
                    continue;
                }
                String root = toVolumeRoot(parts[1]);
                if (root != null) {
                    out.add(root);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] collectFromProcMounts failed: " + t);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final String TAG_LISTENER = "BlockDirsVolume";

    /**
     * 注册 StorageManager 卷状态监听：U 盘/SD 卡插入并挂载后立即触发删除。
     * 这比盲 hook 各种不确定的方法名可靠得多（不同 ROM 方法名千差万别）。
     */
    private static void registerVolumeListener() {
        try {
            Class<?> ctxClass = Class.forName("android.app.ActivityThread");
            Object app = XposedHelpers.callStaticMethod(ctxClass, "currentApplication");
            if (app == null) {
                XposedBridge.log("[" + TAG_LISTENER + "] no application context, skip listener");
                return;
            }
            Object sm = XposedHelpers.callMethod(app, "getSystemService", "storage");
            if (sm == null) {
                return;
            }
            // 构造 StorageVolumeCallback
            Class<?> cbClass = XposedHelpers.findClassIfExists(
                    "android.os.storage.StorageManager$StorageVolumeCallback",
                    sm.getClass().getClassLoader());
            if (cbClass == null) {
                XposedBridge.log("[" + TAG_LISTENER + "] StorageVolumeCallback not found, skip");
                return;
            }
            Object callback = XposedHelpers.newInstance(cbClass);
            XposedHelpers.findAndHookMethod(cbClass, "onVolumeStateChanged",
                    new Object[]{android.os.storage.StorageVolume.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        Object vol = param.args[0];
                                        if (vol == null) {
                                            return;
                                        }
                                        Object dir = null;
                                        try {
                                            dir = XposedHelpers.callMethod(vol, "getDirectory");
                                        } catch (Throwable ignored) {
                                        }
                                        if (dir == null) {
                                            try {
                                                dir = XposedHelpers.callMethod(vol, "getPath");
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                        if (dir != null) {
                                            String root = toVolumeRoot(dir.toString());
                                            if (root != null) {
                                                XposedBridge.log("[" + TAG_LISTENER
                                                        + "] volume state changed -> " + root);
                                                pushDeleteNames();  // 先刷新最新勾选
                                                nativeDeleteOnVolume(root);
                                            }
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log("[" + TAG_LISTENER + "] cb error: " + t);
                                    }
                                }
                            }});
            XposedHelpers.callMethod(sm, "registerListener", callback);
            XposedBridge.log("[" + TAG_LISTENER + "] StorageManager volume listener registered");
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG_LISTENER + "] registerVolumeListener failed: " + t);
        }
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
        return MEDIA_PROVIDER.equals(packageName);
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

    /**
     * 读取模块 App 写入的配置，把「已开启的目录名」推送给 native。
     *
     * 重要：MediaProvider 进程无法读模块 App 的私有 SharedPreferences
     * （/data/data/top.yixiangren.blockdirs 是 0700，XSharedPreferences 会被拒），
     * 因此首选读取模块 App 写的全局可读镜像文件 /data/local/tmp/blockdirs.conf；
     * 只有镜像文件读不到时才回退到 XSharedPreferences。
     * 未开启主开关时推送空数组，native 即不做删除（等于只阻止创建）。
     */
    private static void pushDeleteNames() {
        try {
            List<String> names = new ArrayList<>();
            boolean autoDelete = false;

            // 首选：读全局可读的镜像文件
            String content = DirConfig.readMirrorFile();
            if (content != null) {
                Map<String, String> kv = parseConf(content);
                autoDelete = "1".equals(kv.get(DirConfig.KEY_AUTO_DELETE));
                if (autoDelete) {
                    for (DirConfig.Dir d : DirConfig.DIRS) {
                        if ("1".equals(kv.get(DirConfig.KEY_DIR_PREFIX + d.name))) {
                            names.add(d.name);
                        }
                    }
                }
                XposedBridge.log("[" + TAG + "] pushDeleteNames(from conf): autoDelete="
                        + autoDelete + ", count=" + names.size());
                nativeSetDeleteNames(names.toArray(new String[0]));
                return;
            }

            // 回退：XSharedPreferences
            de.robv.android.xposed.XSharedPreferences prefs =
                    new de.robv.android.xposed.XSharedPreferences(SELF_PACKAGE, DirConfig.PREFS_NAME);
            prefs.reload();
            autoDelete = prefs.getBoolean(DirConfig.KEY_AUTO_DELETE, false);
            if (autoDelete) {
                for (DirConfig.Dir d : DirConfig.DIRS) {
                    boolean on = prefs.getBoolean(DirConfig.KEY_DIR_PREFIX + d.name, d.defaultOn);
                    if (on) {
                        names.add(d.name);
                    }
                }
            }
            nativeSetDeleteNames(names.toArray(new String[0]));
            XposedBridge.log("[" + TAG + "] pushDeleteNames(from xprefs): autoDelete="
                    + autoDelete + ", count=" + names.size());
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] pushDeleteNames failed: " + t);
        }
    }

    /** 解析 key=value 格式的配置文件。 */
    private static Map<String, String> parseConf(String content) {
        Map<String, String> map = new HashMap<>();
        for (String line : content.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return map;
    }

    /** 供外部主动刷新配置（例如某些 ROM 设置变更后）。 */
    public static void refreshConfig() {
        if (nativeLoaded) {
            pushDeleteNames();
        }
    }

    /**
     * hook 卷挂载相关的 Java 方法，作为「插入 U 盘」的检测点。
     * 不同 Android 版本方法名不同，逐个尝试，hook 到哪个算哪个；
     * 命中后取该卷的路径，调用 nativeDeleteOnVolume 触发删除。
     */
    private static void hookVolumeMount(ClassLoader cl) {
        // 候选方法：MediaProvider / StorageManagerService 中与卷 attach 相关的方法
        String[] methodNames = new String[]{
                "onVolumeMount",
                "attachVolume",
                "onScanVolume",
                "onVolumeCreated",
                "mountVolume",
                "onVolumeStateChanged",
                "updateVolumesForCache",
        };
        String[] classNames = new String[]{
                "com.android.providers.media.MediaProvider",
                "com.android.providers.media.attachedvolume.AttachedVolume",
                "com.android.providers.media.attachedvolume.VolumeCache",
        };

        for (String cn : classNames) {
            Class<?> clazz = null;
            try {
                clazz = XposedHelpers.findClassIfExists(cn, cl);
            } catch (Throwable ignored) {
            }
            if (clazz == null) continue;

            for (String mn : methodNames) {
                try {
                    XposedHelpers.findAndHookMethod(clazz, mn, new Object[]{
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        handleVolumeHook(param);
                                    } catch (Throwable t) {
                                        XposedBridge.log("[" + TAG + "] handleVolumeHook error: " + t);
                                    }
                                }
                            }
                    });
                    XposedBridge.log("[" + TAG + "] hooked " + cn + "#" + mn);
                } catch (Throwable ignored) {
                    // 方法不存在或签名不匹配，跳过
                }
            }
        }
    }

    /** 从 hook 到的卷挂载方法参数里尽力提取存储路径，交给 native 删除。 */
    private static void handleVolumeHook(XC_MethodHook.MethodHookParam param) {
        // 重新拉取一次配置（用户可能刚改了开关）
        pushDeleteNames();

        Object[] args = param.args;
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof String) {
                String s = (String) arg;
                if (s.startsWith("/storage/") || s.startsWith("/mnt/media_rw/")
                        || s.startsWith("/mnt/secure/")) {
                    String root = toVolumeRoot(s);
                    if (root != null) {
                        nativeDeleteOnVolume(root);
                    }
                }
            } else if (arg instanceof StorageVolume) {
                // StorageVolume 在部分版本有 getPath / getDirectory
                try {
                    Object path = XposedHelpers.callMethod(arg, "getPath");
                    if (path instanceof String) {
                        String root = toVolumeRoot((String) path);
                        if (root != null) {
                            nativeDeleteOnVolume(root);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 把任意「卷内路径」裁剪成卷根（/storage/XXXX-XXXX 或 /mnt/media_rw/XXXX-XXXX），
     * 若不是外置卷则返回 null。
     */
    private static String toVolumeRoot(String path) {
        String base;
        String prefix;
        if (path.startsWith("/storage/")) {
            prefix = "/storage/";
            base = path.substring("/storage/".length());
        } else if (path.startsWith("/mnt/media_rw/")) {
            prefix = "/mnt/media_rw/";
            base = path.substring("/mnt/media_rw/".length());
        } else if (path.startsWith("/mnt/secure/")) {
            prefix = "/mnt/secure/";
            base = path.substring("/mnt/secure/".length());
        } else {
            return null;
        }
        // 排除内部共享存储
        if (base.startsWith("emulated")) {
            return null;
        }
        int slash = base.indexOf('/');
        String uuid = slash < 0 ? base : base.substring(0, slash);
        if (uuid.isEmpty()) return null;
        return prefix + uuid;
    }

    private static native void installNativeHook();

    private static native void nativeSetDeleteNames(String[] names);

    private static native void nativeDeleteOnVolume(String volumeRoot);
}
