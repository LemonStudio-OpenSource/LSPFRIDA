# LSPFRIFA 真机验证清单（工作流 1/2/3 + D5 尺寸，2026-09-26）

> 前置：`git 458c8ac` 之后的一批改动（工作流 1 C-解耦Provider / 工作流 2 导入+模块化 / 工作流 3 A-时机提前）
> **全部为静态验证态（`tools/check-kt.sh` 双绿），从未编译、从未真机**。
> 本文是这批改动的验收口径。**DSH 侧无 JDK/adb，编译与真机一律用户侧执行**。
> 逐项打勾；任一失败按 §F 回传（抓完整日志行 + 时间戳）。

---

## 0. 编译（最先做，失败即止）

```bash
sh gradlew --no-daemon :app:assembleDebug
```
- 预期：`BUILD SUCCESSFUL`，产出 `app/build/outputs/apk/debug/app-debug.apk`（当前只有 `output-metadata.json`，无 APK）。
- 常见已知坑（勿误判为本次改动引入）：raw string `$` 转义、CMake 4.1.2 环境、MTE 链接偶发——重试/清 `.cxx`。
- 若报"未解析符号"：先看是否 `tools/check-kt.sh` 已覆盖（本次已双绿，故大概率是 IDE 增量缓存 → `sh gradlew --no-daemon clean :app:assembleDebug`）。

## A. 工作流 1（C — 解耦 Provider + D14 早期日志）

| # | 操作 | 预期日志（ad-hoc 目标应用 logcat / 模块日志页） |
|---|---|---|
| A1 | 启用一个目标 → 冷启动该应用 | `event=target_check_remote pkg=<pkg> enabled=true`（**读 remote prefs 成功，不再依赖 Provider**） |
| A2 | 同上，观察脚本来源 | `event=script_group_read_failed` **不出现**；`event=load_persisted_script pkg=<pkg>` 出现 |
| A3 | 永久停用宿主应用（force-stop）后冷启动目标 | 注入仍成功（remote prefs 不依赖宿主进程存活，D4）；`event=provider_probe` 不出现即证明 Provider 未被动用 |
| A4 | 从升级前状态（只有旧键 `script.<pkg>`，无 `scriptgroup.<pkg>` 指针）冷启动 | `event=load_persisted_script` 出现（**旧键回退路径生效**，数据无丢失） |
| A5 | 目标应用日志页，检查早期日志 | 早期阶段日志（`early_*`）在应用启动后**一次性补发**到宿主 UI，不丢（D14 ring buffer 上限 500 行，`EarlyLogBuffer.CAPACITY=500`） |
| A6 | 同 A5，数一下补发行数 | 若早期日志超过 500 行（极不可能），只保留最后 500 行——**属预期**，非缺陷 |

## B. 工作流 2（导入 + 脚本库 + 熔断 D12）

| # | 操作 | 预期 |
|---|---|---|
| B1 | 设置/应用内 → 用**文件**导入一个 `.js`（SAF） | 出现导入预览；确认后入库；`origin=file:<uri>` |
| B2 | 复制一段 JS → 用**剪贴板**导入 | 同上，`origin=clip` |
| B3 | 在浏览器/文件管理器里对 `.js` 选"分享 → LSPFRIFA" | 进入 `import_target` 页；**只入库** 或 **应用到某目标** 二选一 |
| B4 | B3 选"应用到目标" | 旧脚本自动备份（`script.<pkg>.bak.<ts>`）；新脚本生效 |
| B5 | 导入一个**空文件** | 提示"导入失败：内容为空" |
| B6 | 导入一个**二进制文件**（如 .png） | 提示"导入失败：疑似二进制文件" |
| B7 | 导入 >1MB 的文本 | 提示"导入失败：超过 1MB"（`ScriptImport.HARD_LIMIT_BYTES`） |
| B8 | 导入**内容完全相同**的脚本两次 | 去重命中（`lib.<id>.sha256`），不产生两条 |
| B9 | 详情页「脚本库」行 → 进入库页 | 列表/应用/删除/空态四态正常；"应用"可直接作用到当前目标 |
| B10 | 库页删除一个模块 | 从列表消失；已应用该模块的目标脚本相应更新 |
| B11 | 项目详情页右上角🗑 → 确认弹窗 → 删除 | 项目从列表移除 + 自动停用 + 卸载脚本（三重清理） |
| B12 | **D12 熔断**：故意写入一个语法错误的脚本，连续冷启动目标 **3 次** | 第 3 次后详情页出现熔断卡"因加载失败已暂停注入"；目标侧日志 `event=circuit_open_skip pkg=<pkg>` |
| B13 | 点「恢复注入」 | 计数清零 + 闸删除；下次冷启动目标恢复正常注入 |
| B14 | 熔断期间冷启动目标 | `event=circuit_open_skip`（不再尝试注入，防砖） |

## C. 工作流 3（A — 时机提前，onPackageReady）

| # | 操作 | 预期日志顺序 |
|---|---|---|
| C1 | 冷启动已启用目标 | `event=early_router_ready pkg=<pkg>` **先于** `event=application_created pkg=<pkg>` |
| C2 | 同上 | `event=early_script_loaded pkg=<pkg> size=<n>` 出现（脚本在早期阶段就读到） |
| C3 | 同上 | App 阶段 `event=skip_reload_early_loaded pkg=<pkg>` 出现（**不重复加载**，幂等） |
| C4 | 同上 | `event=early_router_taken_over pkg=<pkg>` 出现（Runtime/Hooker 在 App 阶段补挂，`attachRuntime`） |
| C5 | 同上 | 最终 `[lsp-hook] ARMED ...` 仍出现；**B1/B2 回归**：现有观察/替换/overload 模板全绿 |
| C6 | 冷启动**未启用**目标 | `event=early_check_skip pkg=<pkg> enabled=false`；无 ARMED |
| C7 | 冷启动**熔断中**目标 | `event=early_circuit_open_skip pkg=<pkg>`；无尝试 |
| C8 | 目标**无脚本**（启用但空） | `event=early_no_script pkg=<pkg>`（附注"App 阶段可走 Provider 回退"）→ App 阶段应能补上 |
| C9 | 同一次启动内重启目标两次 | `event=early_skip_already pkg=<pkg>`（同一进程内不重复早期注入） |
| C10 | **关键回归**：早期阶段引擎加载失败时 | `event=early_inject_failed pkg=<pkg> err=...` 或 `event=early_script_load_failed` → **App 阶段必须继续尝试**（`earlyScriptLoaded` 只在成功时置位）→ 最终仍 ARMED |
| C11 | 全局回归：`EARLY_INJECT_ENABLED = false` 重编译 | 行为**完全等同工作流 3 之前**；日志无 `early_*`。**这是回滚路径，务必验一次** |
| C12 | 检测平台限制 | 若目标应用 `android:process` 为独立进程/被 LSPosed 作用域排除 → 按设计不注入，非缺陷 |

## D. D5 脚本尺寸实测（必须实测，禁止推理代替）

**协议**：分别生成 4 个合成脚本（各含注释填充至目标体积，末尾一行 `console.log("[size] N KB loaded")`），
依次导入并冷启动目标，记录三件事：

| 脚本体积 | 期望（当前设计假设） | 记录 1：`event=load_persisted_script` | 记录 2：宿主 `Failed to commit changes to framework` | 记录 3：目标内 `[size] N KB loaded` |
|---|---|---|---|---|
| 200 KB | 安全 | | | |
| 500 KB | 允许+警告 | | | |
| 900 KB | 设计称"拒绝" | | | |
| 1.2 MB | 设计称"拒绝" | | | |

**回填**：把 4×3 结果写回 `docs/Modules-And-Early-Injection-Design.md` 的 D5 小节，并把设计阈值改为**实测值**。
> 提醒：限制是 **remote prefs group 级**（D15 已做 group 分离，故单包脚本体积不再互相影响），但 **group 上限仍是硬约束**。

## E. 环境/UI 回归（低成本，顺手做）

| # | 操作 | 预期 |
|---|---|---|
| E1 | 路由走查 7 条 | main / select_project / project_detail / script_editor / logs / **script_library** / **import_target** 全部可达且返回正常 |
| E2 | 详情页熔断状态轮询 | 熔断由目标侧上报驱动，可能随时断闸 → 详情页应自动出现熔断卡（无需手动刷新） |
| E3 | 编辑器导入浮层 | 导入预览弹窗正常展示（**历史坑**：曾被 `lastIndexOf('}')` 插入破坏结构，现应整体重建无误） |
| E4 | 注入提示 t14（历史待验项） | 设置开 → 冷启动目标 → 应用内 Toast「LSPFRIFA 已注入: 包名」；关 → 不弹 |

## F. 失败回传格式

```
场景编号：B12
步骤：第 3 次冷启动后
完整日志行（含时间戳）：
  09-26 07:21:33.123 12345 12345 I LSPFRIFA: event=...
截图：detail 页熔断卡（如适用）
```

## G. 判定与后续门槛

- **A/B/C/E 全绿 + D 回填完成** → 工作流 1/2/3 正式验收，才允许开始 **工作流 4（类加载感知/D3 三级再武装）**。
  > 理由（技术）：工作流 4 的"再武装队列"挂在工作流 3 的早期注入/`attachRuntime` 生命周期上，
  > 若 3 未真机验证就叠 4，一旦出问题无法区分是 3 还是 4（本项目已有"未编译改动堆积"三次教训）。
- **C11 必须验**：`EARLY_INJECT_ENABLED=false` 是工作流 3 的唯一回滚开关。
- **D 必须实测**：D5 阈值是当前唯一的"推理代替实核"遗留项。

## H. 已知限制（勿当 bug 报）

1. devkit QuickJS **无 timer**（`setInterval/setTimeout` 未实现）→ 心跳类探针不判失败；所有定时逻辑在 Kotlin 侧。
2. 对象参数/返回值仍为 `__obj` 占位（B1/B2 既定边界）。
3. 远程文件通道**只读**（宿主无法向目标推送文件）→ 脚本分发必须走 remote prefs。
4. remote prefs **无事务语义**：`Editor.apply()` 失败仅打日志 → 写入靠**读回验证**。
5. 模块化拼装（`modules.<pkg>`）**键已建但未接入注入链**（用户已明确暂缓，`setModules`/`enabledModules` 暂为未调用）→ 库页"应用"按脚本正文生效，不按模块粒度生效。
6. 工作流 4 未实施 → 脚本 hook **未加载的应用类**仍然 `MISS`（这是工作流 4 要解决的）。