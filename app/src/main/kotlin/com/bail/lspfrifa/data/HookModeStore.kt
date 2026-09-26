package com.bail.lspfrifa.data

import android.content.Context
import com.bail.lspfrifa.FrameworkState
import com.bail.lspfrifa.ipc.ScriptStore

/**
 * D3③「类加载感知」开关（宿主侧设置）。
 *
 * ## 它控制什么
 * 目标进程是否 hook ClassLoader.loadClass(String, boolean)，从而把“类加载后多久能挂上”
 * 从轮询的 200ms 降到下一个消息循环。
 *
 * ## 为何单独开关且默认关闭（激进模式）
 * loadClass 是 JVM 最热路径之一：目标 App 每次类加载都会进入我们的拦截。
 * 虽然稳态下开销就是一次队列判空，但它毕竟在别人的关键路径上，
 * 因此默认关闭，由用户显式开启。
 *
 * ## 开关如何到达目标进程
 * 与 hint_inject 完全同构：写 remote prefs 的 lspfrifa_config 组（目标进程只读），
 * 目标进程在下一次注入时读取并决定是否安装监听。
 * **注意**：本开关是“下次注入时生效”，不支持对已在运行的目标即时生效
 * （那需要新增 AIDL 方法，违背 D13“本批不改 AIDL”的约束）。
 */
object HookModeStore {
    private const val PREFS = "lspfrifa_settings"

    /** 与目标侧 LSPFRIFAModule.KEY_LOADCLASS_WATCH 严格一致。 */
    const val KEY_LOADCLASS_WATCH = "loadclass_watch"

    @Volatile
    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /** 默认关闭（激进模式需用户显式开启）。 */
    fun isLoadClassWatchEnabled(): Boolean =
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(KEY_LOADCLASS_WATCH, false) ?: false

    fun setLoadClassWatchEnabled(v: Boolean) {
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_LOADCLASS_WATCH, v)?.apply()
        try {
            FrameworkState.current()
                ?.getRemotePreferences(ScriptStore.REMOTE_GROUP)
                ?.edit()?.putBoolean(KEY_LOADCLASS_WATCH, v)?.apply()
        } catch (_: Throwable) {
            // 框架不可用时静默：与默认关闭自洽（remote 缺键 = 关）
        }
    }
}
