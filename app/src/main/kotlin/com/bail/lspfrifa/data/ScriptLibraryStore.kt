package com.bail.lspfrifa.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.MessageDigest

/**
 * D8/D9：脚本模块库（宿主侧）。
 *
 * 「模块」= 一段可独立启用/禁用的 JS 源码，带显示名与来源标记；
 * 导入（D6/D7）与模块管理共用本存储 —— 这就是把两者合并为一个工作流的依据。
 *
 * ## 为何存本地（而非 remote prefs）
 * 库里是「源文件」，只给宿主 UI 用；真正要下发到目标进程的是
 * [com.bail.lspfrifa.ipc.ScriptStore] 拼装后的结果。
 * 这样库存多少模块都不占 Binder（避开 D5 的 group 大小约束）。
 *
 * 键约定见 docs/Modules-And-Early-Injection-Design.md 第2节。
 */
object ScriptLibraryStore {

    private const val TAG = "LSPFRIFA-ScriptLib"
    private const val PREFS = "lspfrifa_library"

    private const val KEY_INDEX = "lib.index"
    private fun nameKey(id: String) = "lib." + id + ".name"
    private fun codeKey(id: String) = "lib." + id + ".code"
    private fun originKey(id: String) = "lib." + id + ".origin"
    private fun addedKey(id: String) = "lib." + id + ".addedAt"
    private fun shaKey(id: String) = "lib." + id + ".sha256"

    /** 库条目（源码只在使用时读，避免大字符串常驻）。 */
    data class Entry(
        val id: String,
        val name: String,
        val origin: String,
        val addedAt: Long,
        val sha256: String,
    )

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- 查询 ----

    /** 全部条目，按加入时间倒序（新导入在前）。 */
    fun list(): List<Entry> {
        val p = prefs() ?: return emptyList()
        val ids = p.getStringSet(KEY_INDEX, emptySet()) ?: emptySet()
        return ids.mapNotNull { id ->
            val name = p.getString(nameKey(id), null) ?: return@mapNotNull null
            Entry(
                id = id,
                name = name,
                origin = p.getString(originKey(id), "").orEmpty(),
                addedAt = p.getLong(addedKey(id), 0L),
                sha256 = p.getString(shaKey(id), "").orEmpty(),
            )
        }.sortedByDescending { it.addedAt }
    }

    fun code(id: String): String? = prefs()?.getString(codeKey(id), null)

    fun exists(id: String): Boolean = prefs()?.contains(codeKey(id)) == true

    // ---- 写入 ----

    /**
     * D7：入库（按内容 sha256 去重）。
     * 命中已有条目时返回其 id 且 added=false；新入库返回 (新id, true)。
     */
    fun add(name: String, code: String, origin: String): Pair<String, Boolean> {
        val p = prefs() ?: return "" to false
        val hash = sha256(code)
        // 去重：同内容不重复入库（仅更新 name/origin）
        list().firstOrNull { it.sha256 == hash }?.let { dup ->
            p.edit()
                .putString(nameKey(dup.id), name)
                .putString(originKey(dup.id), origin)
                .apply()
            Log.i(TAG, "库内已存在同内容脚本，复用 id=" + dup.id)
            return dup.id to false
        }

        val id = "lib" + System.currentTimeMillis() + "" + (0..999).random()
        val ids = (p.getStringSet(KEY_INDEX, emptySet()) ?: emptySet()).toMutableSet().apply { add(id) }
        p.edit()
            .putStringSet(KEY_INDEX, ids)
            .putString(nameKey(id), name)
            .putString(codeKey(id), code)
            .putString(originKey(id), origin)
            .putLong(addedKey(id), System.currentTimeMillis())
            .putString(shaKey(id), hash)
            .apply()
        Log.i(TAG, "入库: id=" + id + " name=" + name + " bytes=" + code.toByteArray(Charsets.UTF_8).size)
        return id to true
    }

    fun rename(id: String, newName: String) {
        prefs()?.edit()?.putString(nameKey(id), newName)?.apply()
    }

    fun remove(id: String) {
        val p = prefs() ?: return
        val ids = (p.getStringSet(KEY_INDEX, emptySet()) ?: emptySet()).toMutableSet().apply { remove(id) }
        p.edit()
            .putStringSet(KEY_INDEX, ids)
            .remove(nameKey(id))
            .remove(codeKey(id))
            .remove(originKey(id))
            .remove(addedKey(id))
            .remove(shaKey(id))
            .apply()
    }

    /** 内容 sha256（去重键；同时用于 UI 展示短指纹）。 */
    fun sha256(code: String): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(code.toByteArray(Charsets.UTF_8)).joinToString("") { b ->
            val v = b.toInt() and 0xFF
            if (v < 16) "0" + Integer.toHexString(v) else Integer.toHexString(v)
        }
    } catch (_: Throwable) {
        ""
    }
}
