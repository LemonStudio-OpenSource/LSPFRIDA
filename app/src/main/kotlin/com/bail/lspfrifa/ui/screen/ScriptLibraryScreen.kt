package com.bail.lspfrifa.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bail.lspfrifa.data.ScriptLibraryStore
import com.bail.lspfrifa.ipc.IpcManager
import com.bail.lspfrifa.ipc.ScriptStore
import com.bail.lspfrifa.ui.component.MiuixPageBackground
import com.bail.lspfrifa.ui.component.UiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * D8/D9：脚本库管理页（导入的脚本在此被真正管理起来）。
 *
 * 为什么必须有（第一性原理）：没有它，导入只能进不能出 ——
 * 用户导入 10 个脚本后无从知道有哪些、也无从删除或复用，库是死的。
 *
 * 本期只做「查看 / 应用 / 删除」（最小闭环）；重命名与模块化拼装留待后续
 * （后者依赖多模块工作流，已明确暂缓）。
 */
@Composable
fun ScriptLibraryScreen(
    targetPackage: String?,
    targetName: String?,
    onBack: () -> Unit,
) {
    var entries by remember { mutableStateOf(ScriptLibraryStore.list()) }
    var pendingDelete by remember { mutableStateOf<ScriptLibraryStore.Entry?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        entries = ScriptLibraryStore.list()
    }

    /** 把库内脚本应用到当前目标（若从详情页进入则 targetPackage 非空）。 */
    fun applyToTarget(entry: ScriptLibraryStore.Entry) {
        val pkg = targetPackage
        if (pkg.isNullOrBlank()) {
            hint = "请从项目详情页进入本页，才能应用脚本"
            return
        }
        scope.launch {
            val code = withContext(Dispatchers.IO) { ScriptLibraryStore.code(entry.id) }
            if (code.isNullOrBlank()) {
                hint = "脚本内容已丢失"
                return@launch
            }
            withContext(Dispatchers.IO) { IpcManager.backupScript(pkg, IpcManager.loadScript(pkg)) }
            val saved = withContext(Dispatchers.IO) { IpcManager.saveScript(pkg, code) }
            hint = when (saved) {
                ScriptStore.SaveResult.OK -> "已应用到 " + (targetName ?: pkg)
                ScriptStore.SaveResult.LOCAL_ONLY -> "仅存本地（框架未连接）；激活后自动下发"
                ScriptStore.SaveResult.SIZE_EXCEEDED -> "超出下发上限 400KB，未应用"
                ScriptStore.SaveResult.REMOTE_REJECTED -> "框架拒绝写入（容量已满），未应用"
            }
        }
    }

    Scaffold(
        containerColor = MiuixPageBackground(),
        topBar = {
            SmallTopAppBar(
                title = "脚本库",
                subtitle = if (entries.isEmpty()) "空" else entries.size.toString() + " 个脚本",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = UiTokens.PagePadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            hint?.let {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Spacer(Modifier.height(10.dp))
            }

            if (entries.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    Text("暂无脚本", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "可在脚本编辑页用「导入」从文件或剪贴板添加，也可从其他应用分享脚本进来",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                entries.forEach { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = UiTokens.CardRadius,
                        insideMargin = PaddingValues(
                            horizontal = UiTokens.CardMarginH,
                            vertical = 12.dp,
                        ),
                        onClick = { applyToTarget(entry) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    entry.origin,
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "#" + entry.sha256.take(8),
                                    fontSize = 10.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { pendingDelete = entry }) {
                                Icon(
                                    imageVector = MiuixIcons.Delete,
                                    contentDescription = "删除",
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingDelete?.let { entry ->
        WindowDialog(
            show = true,
            title = "删除脚本",
            summary = "将从脚本库移除「" + entry.name +
                "」。已应用到项目的副本不受影响（项目保留的是注入产物）。",
            onDismissRequest = { pendingDelete = null },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { pendingDelete = null }, modifier = Modifier.weight(1f)) { Text("取消") }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            ScriptLibraryStore.remove(entry.id)
                            pendingDelete = null
                            reload()
                            hint = "已删除「" + entry.name + "」"
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("删除") }
                }
            }
        }
    }
}
