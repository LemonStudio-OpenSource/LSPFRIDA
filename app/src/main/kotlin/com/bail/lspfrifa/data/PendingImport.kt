package com.bail.lspfrifa.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * D6：外部「分享进入」的待处理脚本（进程内单例）。
 *
 * ## 为何立刻读完再存
 * 从其他 App 分享进来时（ACTION_SEND / ACTION_VIEW），`EXTRA_STREAM` 的 Uri 读权限
 * 依附于本次 Intent 授权；若只存 Uri 等 UI 起来后再读，可能已失效
 * （设计文档风险表 R8 已标注）。故 [capture] 在 Intent 到达的当下就把字节读完，
 * 只把解析结果留在这里 —— 未经解析的原始 Uri 不进入 UI 层。
 *
 * ## 与库/目标的关系
 * 分享进来的脚本**没有目标上下文**（不知道用户想应用到哪个包），
 * 因此 UI 层需询问：仅入库，或入库并应用到某个已添加的目标。
 */
object PendingImport {

    private const val TAG = "LSPFRIFA-PendingImport"

    /** 待处理项：解析结果 + 来源 App 包名（展示用）。 */
    data class Item(
        val result: ScriptImport.Result,
        val sourcePackage: String?,
    )

    @Volatile
    private var pending: Item? = null

    fun peek(): Item? = pending

    fun take(): Item? {
        val item = pending
        pending = null
        return item
    }

    fun clear() {
        pending = null
    }

    /**
     * 从 Intent 捕获脚本内容（ACTION_SEND / ACTION_VIEW）。
     * @return 是否确实捕获到内容（供调用方决定是否需要提示）。
     */
    fun capture(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action
        val isSend = Intent.ACTION_SEND == action
        val isView = Intent.ACTION_VIEW == action
        if (!isSend && !isView) return false

        val sourcePkg = try {
            intent.`package`
        } catch (_: Throwable) {
            null
        }

        // ---- 1. 流（文件） ----
        val uri: Uri? = @Suppress("DEPRECATION")
        (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            ?: intent.data?.takeIf { isView }
        if (uri != null) {
            val outcome = ScriptImport.readUri(context, uri, sourcePkg)
            if (outcome is ScriptImport.Outcome.Ok) {
                pending = Item(outcome.result, sourcePkg)
                Log.i(TAG, "已捕获分享脚本(流): " + outcome.result.suggestedName)
                return true
            }
            Log.w(TAG, "分享流解析失败，尝试文本回退")
        }

        // ---- 2. 文本 ----
        val text: String? = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            val outcome = ScriptImport.readText(
                text,
                "shared.js",
                ScriptImport.Origin.share(sourcePkg),
            )
            if (outcome is ScriptImport.Outcome.Ok) {
                pending = Item(outcome.result, sourcePkg)
                Log.i(TAG, "已捕获分享脚本(文本) bytes=" + outcome.result.bytes)
                return true
            }
        }
        return false
    }
}