# BlockDirs

阻止手机在插入 OTG U 盘 / SD 卡等**外置可移动存储**时，自动在其根目录创建 `Android`、`Music`、`Pictures`、`DCIM`、`Movies` 等标准媒体目录的 LSPosed 模块。

> 已在 ColorOS 16（Android 16，实测 `PJZ110` / ColorOS V16.1.0）上端到端验证通过；理论上基于 AOSP 的系统也可使用。

---

## 背景

Android 的 MediaProvider（`com.android.providers.media.module`，UID `10176`）在检测到外置存储卷首次挂载时，会在卷的根目录初始化一批标准媒体目录（`Android`、`Music`、`Pictures`、`DCIM`、`Movies` 等）。这些目录的创建并非走 Java 层的 `File.mkdir`，而是由 MediaProvider 通过 **native 层 FUSE** 直接调用 libc 的 `mkdir` / `mkdirat` 完成，因此常规的 Java 层 Hook 无法拦截。

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

## 作者

[yixiangdebufengren](https://github.com/yixiangdebufengren)