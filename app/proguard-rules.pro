# ============================================================================
# LSPFRIFA ProGuard/R8 规则
#
# 本文件只保留「不加就会运行时崩溃或功能失效」的项。
# 每条规则都标注了实核依据（文件:行号 / APK 实测），禁止凭印象添加。
#
# 核对日期：2026-10-06（R8 首次启用）
# ============================================================================

# ---------------------------------------------------------------------------
# [1] libxposed 模块入口 —— 最致命的一条
# ---------------------------------------------------------------------------
# 依据：APK 内 META-INF/xposed/java_init.list 的**内容是硬编码字符串**：
#         com.bail.lspfrifa.xposed.LSPFRIFAModule
#       libxposed 框架在加载模块时按该字符串反射实例化类。
#       若类名被混淆 → 框架找不到入口 → **模块完全失效**（无任何日志）。
# 另：构造函数必须无参（框架调用 newInstance()）。
-keep class com.bail.lspfrifa.xposed.LSPFRIFAModule {
    public <init>();
}
-keepnames class com.bail.lspfrifa.xposed.LSPFRIFAModule

# libxposed API 基类/接口：模块重写的方法（onModuleLoaded/onPackageReady/
# onPackageLoaded）由框架按**签名**调用，方法名不能改。
-keep class io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.api.** { *; }
-dontwarn io.github.libxposed.api.**

# ---------------------------------------------------------------------------
# [2] JNI native 方法 —— 方法名即 C 符号名
# ---------------------------------------------------------------------------
# 依据：app/src/main/cpp/*.cpp 中实现的 6 个导出符号全部为
#         Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeXxx
#       JNI 的静态注册按「类全名 + 方法名」拼符号，任一被混淆即
#       UnsatisfiedLinkError（引擎完全无法初始化）。
# 实测对应符号（6/6 已核）：
#   nativeInitEngine / nativeSetCallback / nativeLoadScript /
#   nativeUnloadScript / nativeCallJs / nativePostOriginalReply
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.bail.lspfrifa.xposed.GumJsBridge {
    *** native*;
}
-keepnames class com.bail.lspfrifa.xposed.GumJsBridge

# ---------------------------------------------------------------------------
# [3] Android 组件 —— 系统按 AndroidManifest 的类名实例化
# ---------------------------------------------------------------------------
# 依据：AndroidManifest.xml 声明的组件（实核）：
#         com.bail.lspfrifa.LSPFRIFAApplication
#         com.bail.lspfrifa.MainActivity
#         com.bail.lspfrifa.provider.ScriptConfigProvider
#       注意：AGP 会自动生成 keep 规则覆盖 Manifest 中声明的组件，
#       这里显式写出是为了防止「组件类名被 renamed 后 Manifest 未同步」
#       的边界情况（AGP 版本差异），零成本兜底。
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# 自定义 Application 的具体实现（供目标进程/宿主进程使用）
-keep class com.bail.lspfrifa.LSPFRIFAApplication { *; }

# ---------------------------------------------------------------------------
# [4] AIDL / Binder IPC 契约 —— 跨进程按接口名 + 方法名解析
# ---------------------------------------------------------------------------
# 依据：app/src/main/aidl/com/bail/lspfrifa/ipc/{ILogReceiver,IScriptExecutor}.aidl
#       AIDL 生成的 Stub/Proxy 通过 DESCRIPTOR（接口全名）与事务方法名通信，
#       且**目标进程与宿主进程各自持有同一份接口**——两侧混淆结果不同就会
#       TransactionTooLarge/UNKNOWN_TRANSACTION 之类诡异错误。
#       跨进程调用链：目标进程 GumJsBridge → ILogReceiver → 宿主 UI
#                    宿主 UI → IScriptExecutor → 目标进程引擎
-keep interface com.bail.lspfrifa.ipc.** { *; }
-keep class com.bail.lspfrifa.ipc.** { *; }
-keep class **.Stub { *; }
-keep class **.Stub$* { *; }
-keep class **.Proxy { *; }

# ---------------------------------------------------------------------------
# [5] Kotlin 元数据 / 协程
# ---------------------------------------------------------------------------
# Kotlin 反射与协程内部依赖 Metadata 注解；删掉会让 kotlinx.coroutines
# 的某些路径抛异常（尤其 suspend 函数 + 反射组合）。
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault,InnerClasses,EnclosingMethod,Signature
-keepattributes *Annotation*

# Kotlin 协程（Compose 大量使用）
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Kotlin 反射（若用到）
-dontwarn kotlin.reflect.**

# ---------------------------------------------------------------------------
# [6] Compose / Miuix / AndroidX —— 库自带 consumer 规则为主，此处补缺
# ---------------------------------------------------------------------------
# Compose 的 @Composable 通过函数签名调用，通常无需 keep；
# 但 ui-tooling 的 Preview 相关类在 release 下会缺失，需 dontwarn。
-dontwarn androidx.compose.ui.tooling.**
-dontwarn androidx.compose.**
-dontwarn top.yukonga.miuix.**

# ---------------------------------------------------------------------------
# [7] sora-editor / TextMate（tm4e）—— 反射加载语法与主题
# ---------------------------------------------------------------------------
# 依据：assets/textmate/ 下的 JSON 由 tm4e 按**配置中的类名/scope 名**解析，
#       sora-editor 的 language-textmate 模块通过 ServiceLoader/反射查找
#       LanguageConfiguration，类名混淆会导致高亮失效（不崩溃但功能没了）。
-keep class io.github.rosemoe.sora.** { *; }
-dontwarn io.github.rosemoe.sora.**
-keep class org.eclipse.tm4e.** { *; }
-dontwarn org.eclipse.tm4e.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
-keep class jdk.internal.** { *; }
-dontwarn jdk.internal.**

# sora-editor 的 View 由 XML/代码动态创建，保留构造器
-keepclasseswithmembers class io.github.rosemoe.sora.widget.CodeEditor {
    public <init>(...);
}

# ---------------------------------------------------------------------------
# [8] 保留调试信息（Release 崩溃栈可读）
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
# 隐藏原始源文件名（不影响行号定位）
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# [9] 枚举 / 序列化安全
# ---------------------------------------------------------------------------
# 枚举的 values()/valueOf() 在部分混淆配置下会失效（R8 已较好处理，
# 但 Kotlin enum + when 组合历史上出过问题，零成本保留）。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable CREATOR（跨进程传递时按字段名反射）
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ---------------------------------------------------------------------------
# [10] 不优化/不警告项
# ---------------------------------------------------------------------------
# 框架 API 在 compileOnly 引入（运行时由 LSPosed 提供），R8 会报缺失
-dontwarn io.github.libxposed.**

# 本模块刻意保留的未调用 API（如 uninstallClassLoaderWatcher 预留）
# —— 不加这条 R8 会把它们删掉
-keep class com.bail.lspfrifa.xposed.HookRouter {
    *** uninstallClassLoaderWatcher(...);
}

# 存储键常量类（键名是跨进程约定，混淆不影响字符串字面量，但保留类更安全）
-keep class com.bail.lspfrifa.ipc.ScriptStore { *; }
-keep class com.bail.lspfrifa.xposed.EarlyLogBuffer { *; }