package com.bail.lspfrifa.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bail.lspfrifa.data.AppsUiState
import com.bail.lspfrifa.data.InstalledApp
import com.bail.lspfrifa.ui.component.AppIconImage
import com.bail.lspfrifa.ui.component.MiuixPageBackground
import com.bail.lspfrifa.ui.component.UiTokens
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 「选择项目」全屏二级页：
 * 异步扫描全部已安装应用，顶部搜索框，右上角排序菜单（应用名/包名/安装时间/更新时间/
 * 倒序 + 隐藏系统应用），点击未添加项 → 写入 addedProjectList 并 popBackStack。
 */
@Composable
fun SelectProjectScreenV2(
    appsState: AppsUiState,
    addedPackages: Set<String>,
    onRetry: () -> Unit,
    onAddProject: (InstalledApp) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.Name) }
    var descending by remember { mutableStateOf(false) }
    var hideSystem by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    // 派生列表：隐藏系统应用过滤 → 搜索过滤 → 排序（含倒序）。纯 UI 层派生，data 不变。
    val visibleApps = remember(appsState, query, sortMode, descending, hideSystem) {
        if (appsState !is AppsUiState.Success) emptyList()
        else {
            val comparator = when (sortMode) {
                SortMode.Name -> compareBy<InstalledApp> { it.name.lowercase() }
                SortMode.Package -> compareBy<InstalledApp> { it.packageName.lowercase() }
                SortMode.Install -> compareBy<InstalledApp> { it.installTime }
                SortMode.Update -> compareBy<InstalledApp> { it.updateTime }
            }
            appsState.apps
                .asSequence()
                .filter { !hideSystem || !it.isSystem }
                .filter {
                    query.isBlank() ||
                        it.name.contains(query, true) ||
                        it.packageName.contains(query, true)
                }
                .sortedWith(if (descending) comparator.reversed() else comparator)
                .toList()
        }
    }

    Scaffold(
        containerColor = MiuixPageBackground(),
        topBar = {
            TopAppBar(
                title = "选择项目",
                largeTitle = "选择项目",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            imageVector = MiuixIcons.Sort,
                            contentDescription = "排序与过滤",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = "搜索应用或包名",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            when (appsState) {
                is AppsUiState.Idle, is AppsUiState.Loading -> LoadingSelectState(
                    Modifier.fillMaxSize()
                )
                is AppsUiState.Error -> ErrorSelectState(
                    Modifier.fillMaxSize(),
                    appsState.message,
                    onRetry,
                )
                is AppsUiState.Success -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(UiTokens.CardSpacing),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    ) {
                        items(visibleApps, key = { it.packageName }) { app ->
                            val added = app.packageName in addedPackages
                            SelectableAppCard(
                                app = app,
                                added = added,
                                onClick = { if (!added) onAddProject(app) },
                            )
                        }
                    }
                }
            }
        }
    }

    // 排序菜单（Miuix 原生弹窗 + DropdownImpl(dialogMode) 行，MIUI 选单风格）：
    // 排序维度 × 4（单选）→ 倒序（toggle）→ 分隔线 → 隐藏系统应用（toggle 不关闭菜单）
    WindowDialog(
        show = showSortMenu,
        title = "排序",
        onDismissRequest = { showSortMenu = false },
    ) {
        Column {
            SortMode.entries.forEachIndexed { index, mode ->
                DropdownImpl(
                    text = mode.label,
                    optionSize = SORT_MENU_ITEM_COUNT,
                    isSelected = sortMode == mode,
                    index = index,
                    dialogMode = true,
                ) {
                    sortMode = mode
                    showSortMenu = false
                }
            }
            DropdownImpl(
                text = "倒序",
                optionSize = SORT_MENU_ITEM_COUNT,
                isSelected = descending,
                index = SortMode.entries.size,
                dialogMode = true,
            ) {
                descending = !descending
                showSortMenu = false
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
            DropdownImpl(
                text = "隐藏系统应用",
                optionSize = SORT_MENU_ITEM_COUNT,
                isSelected = hideSystem,
                index = SortMode.entries.size + 1,
                dialogMode = true,
            ) {
                hideSystem = !hideSystem
                // toggle 不关闭菜单：保留 ✓ 状态反馈，点外部/返回关闭
            }
        }
    }
}

/** 选择页排序维度（与 LSPosed 作用域选择菜单一致）。 */
private enum class SortMode(val label: String) {
    Name("应用名"),
    Package("包名"),
    Install("安装时间"),
    Update("更新时间"),
}

/** 排序菜单总行数（4 维度 + 倒序 + 隐藏系统应用），用于 DropdownImpl 首尾间距计算。 */
private const val SORT_MENU_ITEM_COUNT = 6

@Composable
private fun LoadingSelectState(modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text("正在扫描已安装应用…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun ErrorSelectState(modifier: Modifier, message: String, onRetry: () -> Unit) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("应用列表读取失败", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(message, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun SelectableAppCard(
    app: InstalledApp,
    added: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = UiTokens.CardRadius,
        insideMargin = PaddingValues(horizontal = UiTokens.CardMarginH, vertical = UiTokens.CardMarginV),
        showIndication = !added,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconImage(app.icon, Modifier.size(52.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, fontSize = UiTokens.TitleSize, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(
                    app.packageName,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
            if (added) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "已添加",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
