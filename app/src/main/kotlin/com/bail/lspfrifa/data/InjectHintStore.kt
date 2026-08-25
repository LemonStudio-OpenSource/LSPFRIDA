package com.bail.lspfrifa.data

import android.content.Context

/**
 * 注入成功提示开关（宿主侧设置）：目标进程注入/热更脚本成功后弹 Toast（含包名）。
 * 纯宿主 SharedPreferences（模块本地）；开启状态经 IpcManager.pushScript 随 AIDL
 * loadScript(script, hintInject) 下发到目标进程（跨进程设置不可直读，走参数下发）。
 */
object InjectHintStore {
    private const val PREFS = "lspfrifa_settings"
    private const val KEY = "hint_inject"

    @Volatile
    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /** 默认开启（用户显式关闭才关闭）。 */
    fun isEnabled(): Boolean =
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY, true) ?: true

    fun setEnabled(v: Boolean) {
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY, v)?.apply()
    }
}
