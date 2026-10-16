package top.yixiangren.blockdirs;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一管理「自动删除文件夹」的配置。
 *
 * MediaProvider 进程需要读取本模块 App 写入的开关状态，
 * 因此配置写入一个跨进程可读的 SharedPreferences 文件，
 * MediaProvider 进程通过 XSharedPreferences 读取同名 prefs。
 */
public final class DirConfig {

    /** prefs 文件名，MediaProvider 进程通过 XSharedPreferences 读取 */
    public static final String PREFS_NAME = "blockdirs_config";

    /** 主开关：是否启用「自动删除已存在文件夹」 */
    public static final String KEY_AUTO_DELETE = "auto_delete_enabled";

    /** 每个目录名对应一个开关 key 前缀 */
    public static final String KEY_DIR_PREFIX = "dir_";

    /** 预置的可选目录项 */
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
            new Dir("Movies",     "影片 (Movies)",      false),
            new Dir("Music",      "音乐 (Music)",       false),
            new Dir("Download",   "下载 (Download)",    false),
            new Dir("Downloads",  "下载 (Downloads)",   false),
            new Dir("DCIM",       "相机 (DCIM)",        false),
            new Dir("Pictures",   "图片 (Pictures)",    false),
            new Dir("Recordings", "录音 (Recordings)",  false),
            new Dir("Record",     "录音 (Record)",      false),
            new Dir("Sounds",     "声音 (Sounds)",      false),
            new Dir("Documents",  "文档 (Documents)",   false),
            new Dir("Android",    "系统 (Android)",     false),
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
    }

    public static boolean isDirEnabled(Context ctx, String dirName) {
        boolean def = false;
        Dir d = dirMap().get(dirName);
        if (d != null) def = d.defaultOn;
        return prefs(ctx).getBoolean(KEY_DIR_PREFIX + dirName, def);
    }

    public static void setDirEnabled(Context ctx, String dirName, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_DIR_PREFIX + dirName, enabled).commit();
    }
}
