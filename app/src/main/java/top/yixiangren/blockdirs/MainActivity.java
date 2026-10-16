package top.yixiangren.blockdirs;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

/**
 * BlockDirs 模块主页（视觉复刻 WeKit 风格）。
 *
 * 浅绿主题 + Material 卡片布局 + 居中标题栏。
 * 顶部卡片点击跳转到博客说明页。
 *
 * 新增：设置区加入「自动删除已存在文件夹」主开关，开启后动态展开
 * 各目录的子开关（由 {@link DirConfig#DIRS} 生成）。
 */
public class MainActivity extends Activity {

    private LinearLayout dirListContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Material You 动态取色：跟随系统壁纸取色，新设备生效，旧设备自动降级
        DynamicColors.applyToActivitiesIfAvailable(getApplication());
        setContentView(R.layout.activity_main);
        applyStatusBar();

        bindVersion();
        bindLinks();
        bindHideIconSwitch();
        bindActivationStatus();
        bindUpdateCheck();
        bindAutoDeleteSwitch();
        checkUpdate();
    }

    /**
     * 让状态栏背景跟随动态取色的 colorSurface，并把状态栏图标/文字设为深色。
     * 避免 Material3 默认把状态栏染成偏深蓝/深色的 colorPrimary 造成的突兀感。
     */
    private void applyStatusBar() {
        Window window = getWindow();
        int surface = resolveThemeColor(com.google.android.material.R.attr.colorSurface);
        window.setStatusBarColor(surface);
        boolean isLight = ColorUtils.calculateLuminance(surface) > 0.5;
        int flags = window.getDecorView().getSystemUiVisibility();
        if (isLight) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private int resolveThemeColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true)) {
            return tv.data;
        }
        return Color.WHITE;
    }

    /**
     * 绑定「自动删除已存在文件夹」主开关。
     * 开启后展开目录子开关列表；关闭则收起。
     */
    private void bindAutoDeleteSwitch() {
        dirListContainer = findViewById(R.id.dir_list_container);
        MaterialSwitch master = findViewById(R.id.switch_auto_delete);
        if (master == null || dirListContainer == null) return;

        boolean enabled = DirConfig.isAutoDeleteEnabled(this);
        master.setChecked(enabled);
        renderDirList(enabled);

        master.setOnCheckedChangeListener((buttonView, isChecked) -> {
            DirConfig.setAutoDeleteEnabled(this, isChecked);
            renderDirList(isChecked);
            notifyConfigChanged();
        });
    }

    /** 根据 DirConfig.DIRS 动态生成目录子开关列表。 */
    private void renderDirList(boolean visible) {
        if (dirListContainer == null) return;
        dirListContainer.removeAllViews();
        dirListContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        int marginTop = (int) (getResources().getDisplayMetrics().density * 10);
        for (DirConfig.Dir d : DirConfig.DIRS) {
            View row = inflater.inflate(R.layout.item_dir_switch, dirListContainer, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = marginTop;
            row.setLayoutParams(lp);

            TextView label = row.findViewById(R.id.dir_label);
            label.setText(d.name);

            MaterialSwitch sw = row.findViewById(R.id.dir_switch);
            sw.setChecked(DirConfig.isDirEnabled(this, d.name));
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                DirConfig.setDirEnabled(this, d.name, isChecked);
                notifyConfigChanged();
            });

            dirListContainer.addView(row);
        }
    }

    /**
     * 配置变更后尝试通知 MediaProvider 进程立即刷新（进程若未运行则忽略，
     * 下次被 LSPosed 注入时会重新读取）。
     */
    private void notifyConfigChanged() {
        // MediaProvider 进程在下次卷活动时会重新读取 XSharedPreferences，
        // 这里无需强制重启进程，保持简单可靠。
    }

    /**
     * 绑定「模块激活状态」卡片。
     */
    private void bindActivationStatus() {
        LinearLayout card = findViewById(R.id.card_status);
        TextView title = findViewById(R.id.status_title);
        TextView desc = findViewById(R.id.status_desc);
        ImageView icon = findViewById(R.id.status_icon);
        if (card == null || title == null || desc == null || icon == null) return;

        boolean active = isModuleActive();
        if (active) {
            card.setBackgroundResource(R.drawable.card_status_green);
            icon.setImageResource(R.drawable.ic_status_ok);
            title.setText(R.string.module_status_title_active);
            desc.setText(R.string.module_status_desc_active);
        } else {
            card.setBackgroundResource(R.drawable.card_status_red);
            icon.setImageResource(R.drawable.ic_status_warn);
            title.setText(R.string.module_status_title_inactive);
            desc.setText(R.string.module_status_desc_inactive);
        }
    }

    private boolean isModuleActive() {
        return ModuleActive.isActive();
    }

    /** 绑定「隐藏桌面图标」开关：切换 activity-alias 的 enabled 状态 */
    private void bindHideIconSwitch() {
        MaterialSwitch sw = findViewById(R.id.switch_hide_icon);
        if (sw == null) return;

        boolean hidden = isIconHidden();
        sw.setChecked(hidden);
        sw.setOnCheckedChangeListener((buttonView, isChecked) ->
                setIconHidden(isChecked));
    }

    private boolean isIconHidden() {
        ComponentName alias = new ComponentName(this, getPackageName() + ".MainActivityAlias");
        int state = getPackageManager().getComponentEnabledSetting(alias);
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    private void setIconHidden(boolean hidden) {
        ComponentName alias = new ComponentName(this, getPackageName() + ".MainActivityAlias");
        int newState = hidden
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        getPackageManager().setComponentEnabledSetting(
                alias, newState, PackageManager.DONT_KILL_APP);
    }

    private void bindVersion() {
        TextView footer = findViewById(R.id.version_footer);
        String version = getVersionName();
        if (footer != null) {
            footer.setText(getString(R.string.version_footer).replace("{version}", version));
        }
    }

    private void bindLinks() {
        String repoUrl = "https://github.com/yixiangdebufengren/top.yixiangren.blockdirs";
        int[] cards = {
                R.id.card_guide,
                R.id.card_github,
                R.id.card_blog
        };
        String[] urls = {
                "https://yixiangren.top/463187456",
                repoUrl,
                "https://yixiangren.top"
        };
        for (int i = 0; i < cards.length; i++) {
            final String url = urls[i];
            findViewById(cards[i]).setOnClickListener(v -> openUrl(url));
        }
    }

    private void bindUpdateCheck() {
        findViewById(R.id.card_update).setOnClickListener(v -> manualCheckUpdate());
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    /** 启动时异步检查更新，有新版则弹窗；无更新或失败则静默。 */
    private void checkUpdate() {
        UpdateChecker.check(this, getVersionCode(), (info, error) -> {
            if (info != null) {
                showUpdateDialog(info);
            }
        });
    }

    /** 手动检查更新：弹"正在检查"进度，完成后提示最新或弹更新弹窗。 */
    private void manualCheckUpdate() {
        androidx.appcompat.app.AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.update_checking)
                .setCancelable(false)
                .create();
        progress.show();

        UpdateChecker.check(this, getVersionCode(), (info, error) -> {
            progress.dismiss();
            if (info != null) {
                showUpdateDialog(info);
            } else if (error) {
                snack(R.string.update_check_failed);
            } else {
                snack(R.string.update_dialog_no_update);
            }
        });
    }

    /** 更新弹窗：立即更新 / 忽略本次 / 稍后再说。 */
    private void showUpdateDialog(UpdateChecker.UpdateInfo info) {
        String message = getString(R.string.update_dialog_message,
                getVersionCode(), info.versionCode, info.versionName);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_dialog_title)
                .setMessage(message)
                .setPositiveButton(R.string.update_dialog_now, (d, w) -> {
                    String url = !info.releaseUrl.isEmpty() ? info.releaseUrl : info.downloadUrl;
                    openUrl(url);
                })
                .setNeutralButton(R.string.update_dialog_ignore, (d, w) ->
                        UpdateChecker.ignoreVersion(this, info.versionCode))
                .setNegativeButton(R.string.update_dialog_later, null)
                .show();
    }

    /** Material 风格短提示（Snackbar）。 */
    private void snack(int resId) {
        View root = findViewById(android.R.id.content);
        if (root != null) {
            Snackbar.make(root, resId, Snackbar.LENGTH_SHORT).show();
        }
    }

    private String getVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "1.0";
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0";
        }
    }

    private int getVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 1;
        }
    }
}