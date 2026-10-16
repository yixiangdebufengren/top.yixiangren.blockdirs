# BlockDirs

阻止手机在插入 OTG U 盘 / SD 卡等**外置可移动存储**时，自动在其根目录创建 `Android`、`Music`、`Pictures`、`DCIM`、`Movies` 等标准媒体目录的 LSPosed 模块。

> 已在 ColorOS 16（Android 16，实测 `PJZ110` / ColorOS V16.1.0）上端到端验证通过；理论上基于 AOSP 的系统也可使用。

---

## 背景

Android 的 MediaProvider（`com.android.providers.media.module`，实测 UID `10192` / `u0_a192`）在检测到外置存储卷首次挂载时，会在卷的根目录初始化一批标准媒体目录（`Android`、`Music`、`Pictures`、`DCIM`、`Movies` 等）。这些目录的创建并非走 Java 层的 `File.mkdir`，而是由 MediaProvider 通过 **native 层 FUSE** 直接调用 libc 的 `mkdir` / `mkdirat` 完成，因此常规的 Java 层 Hook 无法拦截。

## 原理

本模块 Hook 位于 **MediaProvider 进程**内的 libc `mkdir` / `mkdirat` 符号（基于 ShadowHook 的 inline hook），采用**位置判定法**而非「黑名单目录名」：

- 仅当目录路径满足以下条件时才拦截：
  - 路径前缀为 `/storage/`、`/mnt/media_rw/` 或 `/mnt/secure/`；
  - 且排除内部存储 `emulated`；
  - 且是「卷 UUID 之后仅一层」的目录名；
- 命中后**返回 0（假装成功）但不真正创建目录**。

### 设计要点

- **不硬编码目录名，零遗漏**：任何第一层目录都会被拦，不存在「漏掉某个标准目录名」的风险。
- **仅拦截外置可移动存储**：手机内部存储 `/storage/emulated` 完全不受影响。
- **更深层子目录不受影响**：只拦卷根下的第一层，`XXX/Music/...` 这类子路径不拦。
- **进程级隔离**：Hook 只安装在 MediaProvider 进程内，你用自己的文件管理器手动建目录走的是另一个进程，不会被拦。
- **不会死循环**：`mkdir` 返回 0（成功）是终止信号，调用方收到「成功」不会重试，因此不会出现「反复创建」的现象。

### 副作用（请知悉）

- 外置卷根目录下任何**第一层目录**都会被拦截，包括你手动在卷根下新建的目录（但手动目录由文件管理器进程创建，实际不受影响，此条仅为 hook 语义说明）。

---

## 构建

### GitHub Actions

仓库已配置 `build.yml`，push 后会自动构建 `arm64-v8a` 的 Release APK（release 复用 debug 签名，便于覆盖安装）。

### 本地构建

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

环境：AGP 8.2.0、Gradle 8.6、JDK 17、NDK 27.2.12479018、CMake 4.0.2（native 以 CMake 构建）。

---

## 安装与启用

1. 安装构建出的 APK。
2. 打开 LSPosed，勾选模块（模块已内置作用域推荐，会自动预选 MediaProvider），作用域选择：
   - **系统框架**
   - **com.android.providers.media.module**（MediaProvider）
3. 重启手机（或软重启使 zygote 注入生效）。
4. 插入 OTG U 盘 / SD 卡，观察其根目录不再自动生成媒体目录。

> 若之前安装过旧模块 `io.github.rootuser.no_otg_media_folders`，建议先卸载以避免混淆验证。

---

## 目录结构

```
top.yixiangren.blockdirs/
├── app/                 # Android 应用（LSPosed 入口 + 关于页 UI）
│   └── src/main/
│       ├── java/top/yixiangren/blockdirs/
│       │   ├── MainHook.java      # Xposed 入口（zygote 加载 native + 注入 hook）
│       │   └── MainActivity.java  # 关于页 UI
│       └── res/layout/activity_main.xml
├── native/              # native hook 实现（ShadowHook + libc mkdir/mkdirat）
│   └── blockdirs.cpp
└── ...
```

---

## 真机实测记录（ColorOS 16 / Android 16）

以下为排障过程中得到的、对本模块设计有决定性影响的事实：

### 1. MediaProvider 身份

- 进程名 `com.android.providers.media.module`，**UID 10192（u0_a192）**；
- 附加组包含 **`1023`（media_rw）**，因此对卷根（`media_rw:media_rw 0770`）有读写权限；
- SELinux 域为 `u:r:mediaprovider_app:s0`。

### 2. 外置卷的文件系统类型决定 hook 是否生效

实测两个 U 盘：

| 卷 | 挂载类型 | mkdir 是否经过 libc | hook 能否拦截 |
|---|---|---|---|
| `/storage/0EB8-0B1D` | **`vfat`**（内核直挂） | ❌ 由 VFS → vfat 内核模块完成，**不进 libc** | ❌ 拦不到 |
| `/storage/0EB80A960EB80A96` | **`fuseblk`**（经 `/dev/fuse`） | ✅ 内核发给用户态 FUSE 守护（即 MediaProvider）→ 调 `libc.mkdir` | ✅ 可拦 |

> **重要结论**：`vfat`（FAT32）卷由内核直接处理文件操作，**`mkdir` 不会经过 libc**，因此纯 hook 方案对 vfat 卷无效。
> 这也是「自动删除」改为 **native 主动扫描**（`scan_all_volumes`，不依赖 mkdir 是否被调用）的原因——主动扫描对所有文件系统类型都有效。

### 3. 跨进程配置传递的坑

- MediaProvider **无法**读取模块 App 的私有 `SharedPreferences`（`/data/data/top.yixiangren.blockdirs` 权限 `0700`，`XSharedPreferences` 会被 `Permission denied`）；
- 解决：App 用 `su` 将配置写到全局可读的 **`/data/local/tmp/blockdirs.conf`（0644）**，MediaProvider 直接读该文件；
- 已验证 UID 10192 可成功读取该文件。

### 4. 调试要点

- native 的 `__android_log_print`（tag `BlockDirsNative`）在本 ROM 的 logcat 各缓冲区中**可能完全不可见**，
  因此判断 native 是否运行**不能依赖日志**，应改为检查进程内存：
  ```bash
  grep -E 'shadowhook' /proc/$(pidof com.android.providers.media.module)/maps
  # 出现 [anon:shadowhook-island] / [anon:shadowhook-enter] 即 hook 已建立
  ```
- 修改配置后需**重启 MediaProvider 进程**，native 才会重新读取配置并重新推送删除名单。

---

## 作者

[yixiangdebufengren](https://github.com/yixiangdebufengren)