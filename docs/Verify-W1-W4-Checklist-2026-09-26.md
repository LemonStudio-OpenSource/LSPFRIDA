# LSPFRIFA 真机验证清单（工作流 1/2/3/4 + D5 尺寸，2026-09-26）

> 前置：`git 458c8ac` 之后的一批改动（工作流 1 C-解耦Provider / 工作流 2 导入+模块化 / 工作流 3 A-时机提前）
> **全部为静态验证态（`tools/check-kt.sh` 双绿），从未编译、从未真机**。
> 本文是这批改动的验收口径。**DSH 侧无 JDK/adb，编译与真机一律用户侧执行**。
> 逐项打勾；任一失败按 §F 回传（抓完整日志行 + 时间戳）。

---

## 0. 编译（**已通过，本项仅需复核**）

> **⚠️ 更新于 2026-09-26（W4 交付后）**：上面这份构建（07:32）**早于工作流 4**。
> W4（`f069e7f`：D3 三级再武装 + 新文件 `HookModeStore.kt`）**从未编译过**，
> 且它是全部改动里风险最高的一项（动了 `ClassLoader.loadClass` 拦截 + 新增待挂队列 + 新线程）。
> **因此 §0 现在是真门槛，不再是“仅复核”**：W4 编译必须跑一次，且失败优先怀疑
> `HookRouter.kt`（+245 行）与 `HookModeStore.kt`（新文件）。

> **历史说明（保留）**：本清单初稿曾写"当前无 APK / 从未编译"，**与现场不符**。
> 实测：`_build_run2.log`（07:21）`BUILD SUCCESSFUL in 2m 21s`，产出
> `app/build/outputs/apk/debug/app-debug.apk`（90,385,982 B；dex 内含 `ScriptLibraryScreen`/`ImportTargetScreen`/`EarlyLogBuffer`）。
> `find app/src -newer app-debug.apk` 为空 → **APK 与当前源码（=HEAD）一致，未过期**。
> 07:17 的 `_build_run.log` 曾失败（`ScriptEditorScreenV1.kt:230 Unresolved reference 'Edit'`），
> 缺失的 `top.yukonga.miuix.kmp.icon.extended.Edit`（第 65 行）随后已补齐，07:21 复编通过。

```bash
sh gradlew --no-daemon :app:assembleDebug   # 复核用；增量应秒级
```
- 预期：`BUILD SUCCESSFUL`；APK 时间戳更新。
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
| B4 | B3 选"应用到目标" | 旧脚本自动备份（实际键为 `bak.<pkg>.<ts>`；清单初稿误写为 `script.<pkg>.bak.<ts>`，已更正）；新脚本生效 |
| B4b | 触发 `SaveResult.SIZE_EXCEEDED`：导入 300KB 脚本（**小于导入上限 1MB，故能过校验**）后在编辑器保存 | 提示"已入库，但超出下发上限 400KB"（`ScriptStore.MAX_SCRIPT_BYTES = 400*1024`）→ **1MB 导入上限与 400KB 下发上限不一致，是本清单最容易漏测的一处** |
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
| C12 | 检测平台限制 | 若目标应用 android:process 为独立进程/被 LSPosed 作用域排除 → 按设计不注入，非缺陷 |
| **C13** | **★ 关键：早期脚本的 hook 请求必须被真正处理**。启用目标 + 脚本 hook 一个 framework 类（永远可用），冷启动 | `[lsp-hook] ARMED ...` 应出现在 `event=application_created` **之前**（或至少同一早期阶段），而**不是** “early_script_loaded 出现但永远无 ARMED”。<br>• 为何单列：修复前早期阶段**不注册消息回调**（_messageCallback 仅 TargetIpcServer 构造时赋值），<br>• 早期脚本的 send() 全部静默丢弃，而 App 阶段又因 earlyScriptLoaded 已置位跳过重载 → **hook 永不注册**。<br>• 这是 W3 引入的真实缺陷，修复于本次 fix: 提交；本项就是它的验收口径。 |
| **C14** | **早期日志不丢**：脚本在最早执行阶段打一条 console.log("EARLY-PROBE")，宿主未运行时冷启动 | 日志面板最终**应出现**该条（早期进 EarlyLogBuffer，握手后由 registerLogReceiver 一次性补发）。<br>• 修复前：早期未消费消息只进 logcat、不入缓冲 → 宿主 UI 永久看不到。 |

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
| E1 | 路由走查 6 条 | main / select_project / project_detail / script_editor / logs / **script_library** / **import_target** —— 共 **7 条 route 声明**，但 **`import_target` 不在底部导航内**（由分享 Intent 触发），故导航走查按 6 条，`import_target` 用 §B3 分享路径覆盖 |
| E1b | **C13 变体**：在 LSPosed 作用域内但**未在 LSPFRIFA 内启用**的目标，冷启动后于**本次开机首次**冷启动第二个目标 | `event=binder_handshake_deferred pkg=… err=…` → 后续 `binder_handshake_recovered attempt=…`（迟注册场景，与 §A3 的 force-stop 场景**不同**，易漏）。若连续失败则 `binder_reconnect_giveup` |
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

- **A/B/C/E 全绿 + D 回填完成** → 工作流 1/2/3 正式验收。
- **W4（D3）验收另见 §I**，其风险独立：即使 A/B/C 未全绿也可先跑 I1–I3（队列基础行为），
  但 **I15/I16（与提前注入叠加）必须在 C 组通过后再跑** —— 否则一旦异常无法区分是 W3 还是 W4。
  > 本项目已有"未编译改动堆积"三次教训，故保留"先验旧后验新"的纪律，而非取消它。
- **C11 必须验**：`EARLY_INJECT_ENABLED=false` 是工作流 3 的唯一回滚开关。
- **D 必须实测**：D5 阈值是当前唯一的"推理代替实核"遗留项。

## H. 已知限制（勿当 bug 报）

1. ~~devkit QuickJS **无 timer**（`setInterval/setTimeout` 未实现）~~ **【2026-10-05 更正：误诊】**
   → timer 完整可用；心跳类探针**可正常使用**。Kotlin 侧调度是设计选择而非限制。
   真实缺口：`queueMicrotask` 未定义（已补 shim）。
2. 对象参数/返回值仍为 `__obj` 占位（B1/B2 既定边界）。
3. 远程文件通道**只读**（宿主无法向目标推送文件）→ 脚本分发必须走 remote prefs。
4. remote prefs **无事务语义**：`Editor.apply()` 失败仅打日志 → 写入靠**读回验证**。
5. **模块化拼装未接线（但库页可用）**：`modules.<pkg>` 键已建、`ScriptStore.setModules`/`enabledModules` 已定义**但全项目零调用**（用户已明确暂缓）→ **D9 模块粒度、D10 冲突语义当前均不生效**。**注意**：这不影响库页功能——库页「应用」是把脚本**正文**写入目标脚本（脚本级生效），与"按模块粒度开关"是两件事，勿混为一谈。
6. **工作流 4 已实施（`f069e7f`）但从未编译/真机验证**：脚本 hook **未加载的应用类**不再直接 `MISS`，
   而是入待挂队列（`MISS_CLASS_QUEUED`）等类加载后由三级机制之一挂上。
   若真机上仍只看到 `MISS class=...` 而无 `MISS_CLASS_QUEUED`，说明改动未生效（先查是否编译进包）。
7. **`EARLY_INJECT_ENABLED` 是 `private const val`（编译期常量）**：**没有运行期开关**。§C11 的回滚验证**不是设置项操作**，而是改源码常量为 `false` → **重新编译** → 冷启动观测。同理，它只存在于模块（目标进程）侧，宿主 UI 无法控制。
8. **本次修复的三处缺口（均为 W3/W4 后新增，未经编译）**：
   a. 早期阶段未注册消息回调 → 早期脚本 send() 全部丢弃（见 §C13）；
   b. TargetIpcServer 构造→setHookRouter 间的丢消息窗口（旧版无害因脚本晚加载；提前注入下真丢）→ 已改为**路由经构造器注入**（initialRouter 参数）；
   c. 早期/构造后未消费消息未入 EarlyLogBuffer → 已补（见 §C14）。
   三处的共同验收信号：**C13（早期 ARMED）+ C14（EARLY-PROBE 出现）**。

## I. 工作流 4（D3 类加载感知 / 三级再武装）—— `f069e7f`

> **前置**：§0 必须先过。W4 动了 `ClassLoader.loadClass` 拦截 + 新增待挂队列 + 新线程，
> 是全部改动里风险最高的一项，且**从未编译过**。
>
> **背景（为何要测）**：原行为下脚本 hook 未加载的类只能 `MISS` 并放弃。
> W4 后改为入待挂队列，由三级机制之一在类可解析时自动挂上：
> ①锚点flush（主线程、上限 8 项）②轮询（200ms，上限≈2min）③loadClass监听（默认关）。

### I-a 基础（开关关，走默认轮询路径）

| # | 操作 | 预期日志 |
|---|---|---|
| I1 | 写脚本 hook 一个**尚未加载**的应用类（如 `com.example.target.MainActivity`），冷启动目标后立即看日志 | `[lsp-hook] MISS_CLASS_QUEUED <cls>#<method> (queued) pending=1` —— **关键：不再是裸 `MISS class=`** |
| I2 | 继续用该应用，直到该类被加载 | `[lsp-hook] ARMED_LATE <cls>#<method> src=poll`（默认路径，≤200ms 内）+ 随后出现 `ARMED` |
| I3 | 观察目标 App 是否变慢/卡顿 | 无可见影响（轮询仅在队列非空时跑，队列空时线程已退） |
| I4 | **防崩溃验证**：整个 I1–I3 期间搜日志 | **不得出现 `POLL_ERR`**；目标 App **不得被杀**。
> 为何特别关注：安卓任意线程未捕获异常会杀**整个目标进程**，而轮询线程跑在目标内。 |
| I5 | 观察超时行为（类始终不加载，如写一个不存在的类名） | ~2 分钟后 `[lsp-hook] POLL_GIVEUP pending=N ticks=…`（防线程永久驻留） |
| I6 | 脚本热重载（编辑器点“运行”） | `[lsp-hook] PENDING_CLEARED n=N`（旧待挂请求随重载清掉，防迟到生效） |
| I7 | 回归：现有 observe / replace / overload 模板 | 行为零变化，`ARMED` 照旧 |

### I-b 锚点 flush（第①级）

| # | 操作 | 预期 |
|---|---|---|
| I8 | 在**提前阶段**（W3）就产生 MISS：脚本 hook 一个在 `onPackageReady` 时仍未加载的类 | `MISS_CLASS_QUEUED` 出现于早期阶段；随后 `ARMED_LATE … src=anchor`（截获于 `callApplicationOnCreate` proceed 之前） |
| I9 | 若一次产生 >8 个待挂项 | 前 8 个走 `src=anchor`，剩余的下一轮 `src=poll`（`FLUSH_MAX_ITEMS=8` 是主线程预算，剩余交给轮询） |

### I-c loadClass 监听（第③级，激进模式，默认关）

| # | 操作 | 预期 |
|---|---|---|
| I10 | 设置页开「类加载感知」→ **重启目标**（开关下次注入生效） | 目标侧 `event=loadclass_watch_requested pkg=<pkg>` + `[lsp-hook] CLASSLOADER_WATCH_ON` |
| I11 | 同 I1 场景（hook 未加载类） | `ARMED_LATE … src=loadclass`（比轮询快，无需等 200ms） |
| I12 | 目标内大批量加载类（如启动时） | **无卡顿**（稳态开销 = 一次 `pendingHooks.isEmpty()`）；无 `CLASSLOADER_WATCH_FAIL` |
| I13 | 设置页关「类加载感知」→ 重启目标 | 无 `CLASSLOADER_WATCH_ON`；I1 场景退化为 `src=poll`（轮询兼容，功能不丢） |
| I14 | **重要限制确认**：开着监听时点“运行”热更脚本 | 监听**不会被卸**（它单独持有、不进 `handles`）——故无需重装；
> 反例：若监听与脚本 hook 同生命周期，热更会把它一起卸掉，导致后续新 MISS 无人接手。 |

### I-d 与工作流 3 叠加（**必须 C 组通过后再跑**）

| # | 操作 | 预期 |
|---|---|---|
| I15 | 冷启动已启用目标，脚本含“未加载类 hook” | 日志顺序：`early_router_ready` → `MISS_CLASS_QUEUED` → `application_created` → `ARMED_LATE src=anchor` → `early_router_taken_over` |
| I16 | 同 I15，确认路由接管后 hook 存活 | `early_router_taken_over` 后，`ARMED`/`ARMED_LATE` 的手柄仍生效（业务 hook 能触发）——
> 这是 `attachRuntime` 的真实价值：**仅换了运行期依赖，未重建实例；若这里出错就是手柄丢了** |

### I-e 开关回滚

| # | 操作 | 预期 |
|---|---|---|
| I17 | 设置页开监听后**不重启**目标，直接看日志 | 无 `CLASSLOADER_WATCH_ON`（**开关是“下次注入生效”**，非即时；依据：D13 不改 AIDL） |
| I18 | 监听导致问题时：设置页关掉 + 重启目标 | 退回轮询路径；若仍异常，则是 W4 基础部分（I1–I7）的问题，与监听无关 |

### I-f W4 的已知未完成（勿当 bug）

1. `uninstallClassLoaderWatcher()` **无调用方**（预留给“关开关立即卸载”，但不改 AIDL 就无法触达运行中进程）。
2. 监听安装失败仅降级（`CLASSLOADER_WATCH_FAIL`），不阻断注入——监听本属优化。
3. 待挂队列的**去重维度是 `cls#method#tag`（不含签名）**：同 tag 同方法的不同 overload 视为一项，
   真正区分在挂载时由 `sigs` 完成。若发现“只挂了一个 overload”，先查是否脚本未调 `overload()`。

## J. API 面收敛（第十轮）—— 与 W1–W4 独立，可随时验
| # | 操作 | 预期 |
|---|---|---|
| J1 | 脚本顶层调用 `Java.performNow(fn)` | 不再 `is not a function`；fn 立即执行 |
| J2 | `Java.isMainThread()` | 返回 false（不抛） |
| J3 | 读 `Java.androidVersion` | 字符串 = 设备实际 Android 版本（如 `"15"`） |
| J4 | 调用 `Java.cast(...)`（及 retain/backtrace/enumerate*/deoptimize*/synchronized/scheduleOnMainThread） | `[lsp] unsupported: Java.xxx（本模块为 LSPlant 路由架构…）` 可读错误；**不得**出现 `is not a function` 或静默 undefined |
| J5 | 链式访问 `Java.ClassFactory.use(...)` / `Java.classFactory.get(...)` / `Java.vm` | 同一可读错误指引；**不得**是 `Cannot read property 'use' of null` |
| J6 | 回归：`Java.available` / `Java.perform` / `Java.use(...).implementation` 全链 | 行为与上一轮完全一致 |
| J7 | **日志防静默验证**：搜全量日志 | 不得出现 `Cannot read propert`、`is not a function` 类原始 TypeError（出现即说明有漏网成员） |

## K. 体积与性能（第十一轮 P0）—— 独立可验
| # | 操作 | 预期 |
|---|---|---|
| K1 | 查看 APK 内容 | **只有 `lib/arm64-v8a/`**，无 `lib/armeabi-v7a/` |
| K2 | 记录 APK 体积 | 约 60 MB（原 87 MB，-27 MB） |
| K3 | 日志页：脚本高频输出（engine poll 级）时观察 | 列表流畅追加；**不再卡顿**；日志仍"看起来实时"（≤200ms 延迟） |
| K4 | 日志页长时间停留（≥1 分钟） | CPU 占用低（3s 轮询走 stat 快路径，无全量读盘） |
| K5 | 日志页上下滚动 | 滚动流畅；顶栏 blur 在滚动中消失、松手恢复（P0-3 预期行为，非 bug） |
| K6 | 日志页清除后继续有新日志 | 不自动回灌（cleared 防护） |
| K7 | 日志页进入时已有历史 + 实时正在输出 | 顺序正确（历史在前、实时在后），无错位 |
| K8 | 回归：详情页/编辑页顶栏玻璃效果 | 与之前一致（blurEnabled 默认 true，零回归） |
