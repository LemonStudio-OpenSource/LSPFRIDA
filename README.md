# LSPFRIFA

Android **Xposed/LSPosed + Frida GumJS** 注入框架模块（UI 参考 LSPilot，Miuix Compose 风格）。

- 架构：libxposed api+service 102（LSPlant 引擎）+ NDK 静态 frida-gumjs 17.9.3（QuickJS）+ Miuix 0.9.4-rc01 Compose UI
- 注入链：onPackageLoaded → hook Instrumentation.callApplicationOnCreate → GumJsBridge 注入（LSP shim → 用户脚本）
- 官方通道：`LSP.hook()` → HookRouter → libxposed hook()（ARMED/HIT 已验证）
- 路线 B：frida-java-bridge 语义（`Java.perform` / `Java.use(...).implementation`）运行在官通上（replace 观察/替换返回/async await this.method）

## 构建
```sh
# devkit 依赖（CMake 必需，解压到下方路径）
# frida-gumjs-devkit-17.9.3-android-*.tar.xz → app/src/main/cpp/frida-gumjs-devkit/<abi>/{libfrida-gumjs.a, frida-gumjs.h}
sh gradlew --no-daemon :app:assembleDebug
```
- 需要 AndroidIDE / 具备 JDK17 + Android SDK 构建环境；模块经 LSPosed 激活（minApiVersion 82）。
- 文档：`docs/`（Handoff / RouteB-Facts / RouteB-Plan / RouteB-Verify-List / 各设计与报告）。
