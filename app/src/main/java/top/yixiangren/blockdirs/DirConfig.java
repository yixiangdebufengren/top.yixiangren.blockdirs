package top.yixiangren.blockdirs;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一管理自动删除文件夹的配置。
 *
 * 跨进程传递方案：MediaProvider 进程无法读模块 App 的私有 SharedPreferences
 * （/data/data/top.yixiangren.blockdirs 是 0700），因此改为写一份全局可读的
 * 镜像文件 /data/local/tmp/blockdirs.conf (0644)，MediaProvider 直接读它。
 */
public final class DirConfig {

    private static final String TAG = "BlockDirs";

    public static final String PREFS_NAME = "blockdirs_config";

    /**
     * 跨进程配置镜像文件路径。
     *
     * 关键：写入 MediaProvider 自己的私有目录（而非 /data/local/tmp）。
     * 原因：/data/local/tmp 的 SELinux 标签是 shell_data_file，MediaProvider 的
     * mediaprovider_app 域读它会被 avc 拒绝；而 provider 自己家目录的标签是
     * privapp_data_file，它读自己的文件天然放行，不受任何策略阻拦。
     * 写入需 root（由模块 App 通过 su 完成，写后 chown 给 provider 的 uid）。
     */
    public static final String CONFIG_FILE =
            "/data/user_de/0/com.android.providers.media.module/blockdirs.conf";

    /** 兼容的多用户/旧路径候选，读取时依次尝试。 */
    public static final String[] CONFIG_FILE_CANDIDATES = new String[]{
            "/data/user_de/0/com.android.providers.media.module/blockdirs.conf",
            "/data/data/com.android.providers.media.module/blockdirs.conf",
            "/data/user/0/com.android.providers.media.module/blockdirs.conf",
    };

    public static final String KEY_AUTO_DELETE = "auto_delete_enabled";

    public static final String KEY_DIR_PREFIX = "dir_";

    public static final class Dir {
        public final String name;
        public final String label;
        public final boolean defaultOn;

        Dir(String name, String label, boolean defaultOn) {
            this.name = name;
            this.label = label;
            this.defaultOn = defaultOn;
        }
    }

    public static final Dir[] DIRS = new Dir[]{
            new Dir("Movies",     "\\u5f71\\u7247 (Movies)",      false),
            new Dir("Music",      "\\u97f3\\u4e50 (Music)",       false),
            new Dir("Download",   "\\u4e0b\\u8f7d (Download)",    false),
            new Dir("Downloads",  "\\u4e0b\\u8f7d (Downloads)",   false),
            new Dir("DCIM",       "\\u76f8\\u673a (DCIM)",        false),
            new Dir("Pictures",   "\\u56fe\\u7247 (Pictures)",    false),
            new Dir("Recordings", "\\u5f55\\u97f3 (Recordings)",  false),
            new Dir("Record",     "\\u5f55\\u97f3 (Record)",      false),
            new Dir("Sounds",     "\\u58f0\\u97f3 (Sounds)",      false),
            new Dir("Documents",  "\\u6587\\u6863 (Documents)",   false),
            new Dir("Android",    "\\u7cfb\\u7edf (Android)",     false),
    };

    public static Map<String, Dir> dirMap() {
        Map<String, Dir> map = new LinkedHashMap<>();
        for (Dir d : DIRS) {
            map.put(d.name, d);
        }
        return map;
    }

    private DirConfig() {
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isAutoDeleteEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_DELETE, false);
    }

    public static void setAutoDeleteEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_DELETE, enabled).commit();
        writeMirrorFile(ctx);
    }

    public static boolean isDirEnabled(Context ctx, String dirName) {
        boolean def = false;
        Dir d = dirMap().get(dirName);
        if (d != null) def = d.defaultOn;
        return prefs(ctx).getBoolean(KEY_DIR_PREFIX + dirName, def);
    }

    public static void setDirEnabled(Context ctx, String dirName, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_DIR_PREFIX + dirName, enabled).commit();
        writeMirrorFile(ctx);
    }

    public static void syncMirror(Context ctx) {
        writeMirrorFile(ctx);
    }

    /** 生成镜像文件内容（也供 root 兜底写入使用）。 */
    public static String buildMirrorContent(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(KEY_AUTO_DELETE).append('=')
                .append(isAutoDeleteEnabled(ctx) ? 1 : 0).append('\n');
        for (Dir d : DIRS) {
            sb.append(KEY_DIR_PREFIX).append(d.name).append('=')
                    .append(isDirEnabled(ctx, d.name) ? 1 : 0).append('\n');
        }
        return sb.toString();
    }

    /**
     * 把当前配置写成全局可读的映射文件，供 MediaProvider 进程读取。
     */
    public static void writeMirrorFile(Context ctx) {
        String content = buildMirrorContent(ctx);
        FileOutputStream fos = null;
        try {
            File f = new File(CONFIG_FILE);
            fos = new FileOutputStream(f, false);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            f.setReadable(true, false);
            Log.i(TAG, "mirror config written: " + CONFIG_FILE);
        } catch (Throwable t) {
            Log.w(TAG, "writeMirrorFile failed: " + t);
        } finally {
            closeQuietly(fos);
        }
    }

    /** 读取镜像文件内容（MediaProvider 进程使用；找不到返回 null）。 */
    public static String readMirrorFile() {
        for (String path : CONFIG_FILE_CANDIDATES) {
            String content = readFileQuietly(path);
            if (content != null) {
                return content;
            }
        }
        return null;
    }

    private static String readFileQuietly(String path) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(path);
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(fis, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(fis);
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
