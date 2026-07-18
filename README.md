# Grok Build(安卓手机版 · 路线 A 真机移植)

把 xAI 开源的终端 AI 编码/运维 agent **grok-build** 真实交叉编译为 `aarch64-linux-android`
原生可执行文件,并内嵌终端模拟器在安卓手机上 **1:1 运行原始 TUI**。

> 本项目移植自 xAI 开源项目 [`xai-org/grok-build`](https://github.com/xai-org/grok-build)(Apache License 2.0)。
> 保留原 `LICENSE` 与 `THIRD-PARTY-NOTICES`,未移除任何版权声明。
> 终端渲染/PTY 采用 Termux 的 `terminal-view` / `terminal-emulator` 模块(Apache-2.0)。

---

## 这是什么

- **不是重写**:App 里跑的是**真正的 grok 二进制**(crate `xai-grok-pager-bin`,产物名 `grok`),
  由原始 Rust 源码用 Android NDK 交叉编译得到,以 `libgrok.so` 形式随 APK 打包。
- **终端宿主**:用 Termux 的终端模拟器提供 PTY 与彩色终端渲染,忠实呈现原 groknight 暗色 TUI。
- **多厂商**:grok 原生支持 `base_url + api_key + model` 的 provider 体系。设置页下拉选择厂商,
  自动带出默认 `base_url`/模型(可编辑),各厂商 API Key 分别保存,落成 `~/.grok/config.toml`:
  - OpenAI 兼容厂商 → `api_backend = "chat_completions"`
  - Anthropic(Claude)→ `api_backend = "messages"`(原生 `/v1/messages`,`x-api-key` + `anthropic-version` 头)
- **Root**:用 libsu 请求/检测 root;状态行显示是否已授权(可点击重试);
  设置里可勾选“以 root 运行 grok”,勾选后经 `su -c` 启动 grok,其内建 shell 工具即以 root 落地。
  无 root 时正常以普通用户运行,不崩溃。

---

## 安装与使用

1. 安装 `app-debug.apk`(调试签名,可直接侧载;首次安装需允许“未知来源”)。
2. 打开 **Grok Build**,进入即自动启动 grok TUI;顶部状态行显示 `厂商 · 模型 · Root 状态`。
3. 点右上角 `⋮` → **设置**:
   - 选择**厂商**(xAI / OpenAI / Anthropic / OpenRouter / DeepSeek / Gemini / Ollama / 自定义);
   - 按需修改 `base_url` 与**模型**;
   - 填入该厂商的 **API Key**(按厂商分别保存);
   - 如需 root,勾选“以 root 运行 grok”;
   - 点**保存并重启 grok**。
4. 用中文直接下指令即可;grok 自主调用工具、读取结果并继续推理直到给出最终答复。
   底部一排触屏辅助键(ESC/TAB/CTRL/ALT/方向键/^C/^D/^L 等)方便操作 TUI。

> **模型 ID 会随时间变化**:预设默认值可能失效,失效时请到设置里手动填写当前有效的模型 ID。
> 本 App 不编造模型名——预设值来自原项目与各厂商公开文档。

### 关于登录

原版 grok 默认走浏览器 OAuth 登录;移动端无此流程,本 App 通过 `config.toml` 的
`[model.<厂商>]`(含 `api_key`/`extra_headers`)与环境变量提供凭据,填好 API Key 即可直接用。

---

## 从源码构建

### 前置
- Android SDK(platform-34、build-tools 34.0.0)、**NDK 27.2.12479018**、JDK 17+
- Rust 1.92.0(见 grok-build 的 `rust-toolchain.toml`)、`protoc`、`cargo`

### 第一步:交叉编译 grok 二进制(在 `grok-build` 仓库)
```bash
cd grok-build
rustup target add aarch64-linux-android
export ANDROID_NDK_HOME=<ndk>/27.2.12479018
export PROTOC=/usr/bin/protoc
TC=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
export CC_aarch64_linux_android=$TC/aarch64-linux-android24-clang
export AR_aarch64_linux_android=$TC/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$TC/aarch64-linux-android24-clang
# 关闭 jemalloc 与 landlock sandbox(移动端不适用/无需)
cargo build --release -p xai-grok-pager-bin --target aarch64-linux-android --no-default-features
```
产物:`grok-build/target/aarch64-linux-android/release/xai-grok-pager`

### 第二步:放入 Android 工程并打包(在 `grok2` 仓库)
```bash
mkdir -p app/src/main/jniLibs/arm64-v8a
cp ../grok-build/target/aarch64-linux-android/release/xai-grok-pager \
   app/src/main/jniLibs/arm64-v8a/libgrok.so
./gradlew :app:assembleDebug
```
产物:`app/build/outputs/apk/debug/app-debug.apk`

> **为什么是 `libgrok.so`**:Android 10+ 禁止执行应用可写目录内的文件,
> 只有位于 APK 的 `lib/<abi>/` 目录(安装后即 `nativeLibraryDir`)的文件才能被 `exec`。
> 因此把可执行文件命名为 `lib*.so` 打包,运行时从 `nativeLibraryDir` 直接执行。

### 为让桌面 Rust 代码能交叉编译到 Android 所做的补丁
详见 `工作日志.md`。核心是若干上游 crate 只识别 `target_os = "linux"/"macos"` 而漏了
`"android"`,以及 grok 自身两处 `cfg` 分支。所有补丁集中在
`grok-build/third_party/android-patches/`(以 `[patch.crates-io]` 覆盖)与工作区少量源码,
仅影响 android target,不改变桌面构建。

---

## 目录结构
```
grok2/
├── app/                     # 终端宿主 App(Kotlin)
│   ├── src/main/java/com/grokbuild/terminal/
│   │   ├── MainActivity.kt      # 终端界面 + 会话/输入回调 + 触屏辅助键
│   │   ├── SettingsActivity.kt  # 多厂商设置(下拉 + 各字段 + 各自 Key)
│   │   ├── AboutActivity.kt     # 关于/出处声明
│   │   ├── Providers.kt         # provider 预设 + Prefs 存储
│   │   ├── GrokLauncher.kt      # 定位二进制 + 生成 config.toml + 组装 env + 建会话
│   │   └── RootManager.kt       # libsu root 检测/请求
│   └── src/main/jniLibs/arm64-v8a/libgrok.so   # 交叉编译得到的真实 grok 二进制
├── terminal-view/           # Termux 终端渲染(vendored,Apache-2.0)
├── terminal-emulator/       # Termux PTY/终端仿真 + libtermux(vendored,Apache-2.0)
├── LICENSE                  # 原 grok-build 的 Apache-2.0
├── THIRD-PARTY-NOTICES      # 原第三方声明
└── 工作日志.md              # A/B 决策、假设、逐个补丁记录
```

## 许可证
Apache License 2.0。移植自 xai-org/grok-build;Termux 终端模块亦为 Apache-2.0。

---

## 附:图形 GUI 版(`gui` 模块 · 路线 B)

除上面的终端真机版外,另提供一个 **grok 神似的图形对话 App**(`gui/` 模块,`dist/grok-build-gui.apk`):
原生 Kotlin、软键盘正常唤起,忠实还原 groknight 深色终端观感——彩色角色标签(用户/grok/工具)、
每次工具调用与结果都是独立可见卡片、命令面板式多行输入、顶部状态行。

- **多厂商 · 两种协议真正实现**:统一中立会话抽象(`LlmClient`)封装
  **OpenAI 兼容**(`OpenAiClient`,`/chat/completions` + `tools`/`tool_calls`)与
  **Anthropic 原生**(`AnthropicClient`,`/messages` + content blocks + `x-api-key`/`anthropic-version`);
  agent 工具循环(`Agent`)与厂商解耦。各厂商 Key 分别保存,base_url/模型可编辑。
- **Root 工具**:`run_shell` / `read_file` / `write_file` 经(可选 root)shell 落地,
  路径单引号转义、写入用 base64 传输;无 root 优雅失败不崩溃。
- **全中文**:UI、文案、系统提示词全部简体中文;破坏性命令前要求先说明风险。
- 两种协议的 JSON 序列化/解析与命令引用均有单元测试(`gui/src/test`,`:gui:testDebugUnitTest` 通过)。

构建:`./gradlew :gui:assembleDebug` → `gui/build/outputs/apk/debug/gui-debug.apk`。
