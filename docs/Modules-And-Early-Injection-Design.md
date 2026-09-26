# 脚本模块化 + 注入时机提前 —— 冻结设计（决策已定，实施前不再争论）

> 本卷为**冻结决策卷**。按本项目实核纪律（三查 / 先冻结再实施），实施前所有键名、接口、顺序以本卷为准；
> 与本卷冲突的旧设想（含对话中的临时倾向）一律作废。
> 依据：源码实读（`xposed/*.kt`、`cpp/gumjs_bridge.cpp`、`ipc/*.kt`、`provider/ScriptConfigProvider.kt`、
> `MainActivity.kt`、`ui/screen/*`）+ libxposed 源码实读（`/storage/emulated/0/Download/libxposed_sources/`）。

---

## 0. 第一性原理（决策的唯一依据）

1. **注入 = 三件互不依赖的事被错误地串成了一串**：
   - ① 引擎准备（`loadLibrary` + `GumJsBridge.init`）——只依赖进程存在
   - ② hook 就绪（HookRouter + 脚本加载 + 注册）——依赖引擎 + **targetClassLoader**
   - ③ 宿主协同（`register_ipc` / 日志上 UI / 热更新）——依赖 **Context**
   - ④ 启用判定——只依赖 remote prefs（**无依赖**）
   现在 ①②③④ 全被挂在 `Instrumentation.callApplicationOnCreate` 之后，而其中只有 ③ 真正需要那么晚。
   → **结论：拆分这四段，各自放到最早可行的位置。**

2. **"更早"不能替代"能挂未加载的类"**。LSPlant 需要 ArtMethod 已存在；脚本写任意类必然 MISS。
   → 这是**独立能力缺口**，用"类加载感知 + 待挂队列"解决，不靠提前时机。

3. **目标 App 启动延迟是不可付出的成本**。本项目 F2 修复已把整链移出主线程；任何"提前"若回到主线程即倒退。
   → 提前必须与后台化绑定；且同步代价必须是**有界且条件式**的。

4. **失败模式必须自愈**。单脚本架构下任一模块顶层语法错误 → 整包加载失败 → 目标 App 每次启动都受影响。
   → 需要自动熔断（见 D12），否则用户会陷入"目标应用不可用且不知如何恢复"。

5. **少状态 = 少 bug**。能不新增代码路径就不新增（如"引擎提前加载"不单列，合并进时机改造）。

---

## 1. 冻结决策清单

### D1 — 时间线：做 C 与 A；B 合并进 A；E 不做
| 代号 | 内容 | 处置 |
|---|---|---|
| A | 脚本加载 + hook 挂载前移到 `onPackageReady`（后台线程） | **做**，第 3 工作流 |
| B | 引擎加载前移到 `onPackageLoaded` | **不做为独立项**——合入 A（两个回调间隔毫秒级，单列只增状态） |
| C | 启用判定/脚本来源只用 remote prefs，去掉 Provider 硬依赖 | **做，第 1 工作流**（当前最痛的失败源） |
| D | 类加载感知 + 待挂队列（能力解锁） | **做**，第 4 工作流 |
| E | `onModuleLoaded` 极早介入 | **不做**（无 app CL、判断脆弱、无新增能力） |

**为什么 C 排第一**：失败模式已被真实事件验证（`Unknown authority` 导致握手失败、团队花 t16/t17 修补），
且当前注入链**强依赖宿主进程存活**——宿主未启动/被强停即注入失败。这是"正在流血"的问题，优先于新能力。

**为什么 D 排最后**：价值最高但风险最高（`loadClass` 是极端热点 + 多 CL + 递归），
且当前有可用绕行（类加载后重跑脚本），无事故记录。高风险项放在低风险项之后。

### D2 — 同步 vs 异步：重活永远后台；允许有界条件式同步
- 引擎加载、Provider 握手、脚本解析 → **永远后台线程**（沿用 `isDaemon=true` 模式）。
- **例外（新增）**：在 `Instrumentation.callApplicationOnCreate` 的 intercept 内，`chain.proceed()` **之前**，
  执行一次**有界、条件式**的早期 hook flush：仅当"待挂队列非空"时，同步尝试挂载**已加载类**的 hook。
  队列为空 → 立即返回，成本≈0。
- **不承诺**"脚本一定早于 `Application.onCreate`"。对外口径改为：
  **"hook 就绪与目标生命周期解耦；提前为 best-effort；早期类 hook 通过 flush 保证（已加载者）"**。
- 理由：二选一（要么全同步拖慢启动、要么全异步丢确定性）是伪二分；真正的解是"把同步部分压到条件式且有界"。

### D3 — 类加载感知的实现形态：三级再武装（默认开 ①②，③ 可开关）
1. **锚点 flush（零成本，必做）**：在既有锚点（`callApplicationOnCreate` proceed 前）flush 待挂队列。
2. **Kotlin 侧调度（必做）**：待挂队列非空时，用 Java 侧线程每 200ms 重试；队列空即停止。
   ⚠️ **不能用 JS timer**——devkit QuickJS 未实现 `setInterval/setTimeout`（本项目已实证），故调度必须在 Kotlin/Java 侧。
3. **`ClassLoader.loadClass` 拦截（激进模式，设置项开关）**：前缀白名单 + 待挂队列为空时立即 return +
   **proceed 之后再 post 补挂**（绝不在 intercept 内同步装新 hook，防递归）。

### D4 — 脚本来源：remote prefs 为唯一必需通道（三查后修正）
**三查结论**（`XposedInterface.java:546-564`）：
- `String[] listRemoteFiles()` / `ParcelFileDescriptor openRemoteFile(String name)` —— **均为只读**。
- `XposedService`（宿主侧）只有 `listRemoteFiles` / `openRemoteFile` / `deleteRemoteFile`，
  **没有任何远程文件写入 API**（已穷举 classes.jar 全部类，见 §4 三查清单 #2）。
→ **宿主无法向目标进程写文件。大脚本没有文件通道可用。**

优先级（修正后）：
1. `remote prefs`（模块端只读，不依赖宿主进程）—— **唯一必需通道**
2. `openRemoteFile`（只读；用于读模块 APK 内嵌资源，**不用于下发脚本**）
3. Provider（兜底；仅在 remote prefs 完全不可用时，沿用 F1 鉴权）

### D5 — 脚本大小策略（三查后重写：限制是 group 级，不是脚本级）

**三查结论**（RemotePreferences / RemotePreferences$Editor 反编译）：
- 读：requestRemotePreferences(group) 返回**整个 group** 的 Bundle → getSerializable("map")
- 写：updateRemotePreferences(group, bundle)；buildCommitBundle() 用 putSerializable 打包
  mPut（整个 HashMap）+ mDelete（HashSet）
→ **每次读写都搬运整个 group 的 map。** 因此：

> **真正的上限不是「单脚本大小」，而是「单个 group 的总字节数」（约等于 Binder 事务上限 1MB，含序列化开销）。**

现状 lspfrifa_config 一个 group 里塞了 enabled + script.<pkg>×N + hint_inject：
N 个目标各 100KB → 一次写 = 1MB → **写入被静默丢弃**（Failed to commit changes to framework）。
这是当前架构里**尚未被发现的隐患**，与脚本大小阈值同等重要。

修正策略（配合新增 D15）：

| 阈值 | 处置 |
|---|---|
| 单脚本 ≤ 200KB | 走该包独立 group（D15），安全 |
| 200KB ~ 400KB | 允许但警告（接近上限，以实测为准） |
| 大于 400KB | **拒绝导入并明确报错**（当前架构无下发手段） |
| 硬上限 8MB | 超出直接拒绝 |

**实测协议（必须做，不用推理代替）**：分别导入 200KB / 500KB / 900KB / 1.2MB 四个合成脚本各注入一次，
记录 load_persisted_script 是否出现、宿主侧是否报 Failed to commit changes to framework。依结果回填。

### D6 — 导入通道：文件 + 剪贴板 + 分享 + 脚本库；不做 URL、不做 zip
- **做**：SAF 文件选择（零权限）、剪贴板（零权限）、`ACTION_SEND`/`ACTION_VIEW` 分享进入（零权限）、从脚本库复用。
- **不做 URL 下载**：需新增 `INTERNET` 权限；注入框架宿主增权扩大攻击面，而"浏览器下载→分享/文件导入"已覆盖该需求。
  存储 schema 的 `origin` 字段预留 `"url:"` 形式，将来要加零改动。

### D7 — 导入行为：覆盖 + 自动备份 + 可撤销 + 内容去重
导入 = 入库 + 应用到当前目标（覆盖）。覆盖前自动备份旧脚本；提供"撤销导入"回滚。
库内按 `sha256` 去重（同内容不重复入库，仅更新 name/origin）。

### D8 — 多模块与导入合并为同一工作流
两者共用同一套存储与同一套界面（脚本库即模块库），分开做等于做两遍。

### D9 — 模块粒度：每个目标包 = N 个模块文件
理由：模块级 enable/禁、级日志前缀、级去重、级分享都需要"文件级身份"；
且单文件内写多个模块的用法可被它兼容（后者是前者的真子集）。

### D10 — 同方法冲突语义：observe 扇出；replace 单赢 + 冲突告警
- **observe 语义**：按注册顺序**全部执行**（链式扇出，符合 frida 体验）。
- **replace 语义**：**只有一个赢**——按优先级取最高者执行，其余 observe 类仍全部执行；
  两个模块同时 replace 同一方法 → 记 `[lsp-hook] CONFLICT` 日志并在宿主 UI 标黄。
- 优先级**复用 libxposed 原生机制**（`HookBuilder.setPriority`，`PRIORITY_DEFAULT=50`），不另造优先级表。
  ⚠️ `setPriority` 精确签名待三查（当前仅读到 javadoc 片段）。

### D11 — 隔离：单引擎，不追求抢占隔离
- 单 `GumScript` 单 `HookRouter.handles`，模块以注册表区分（不新增 native 实例）。
  理由：frida 多 script 共用同一 JS 线程，多引擎**拿不到**想要的抢占隔离，成本却线性叠加（内存/加载耗时/JNI/AIDL/rpc id 空间）。
- 真实痛点的替代缓解：
  - **模块级错误归因**：每模块 IIFE 包裹 + 宿主侧预校验（语法/大小/编码）+ 日志与异常信息带 `moduleId`。
  - 已知不可解：模块内死循环会僵死整个 JS 线程（文档化，不假装解决）。

### D12 — 熔断安全阀（防砖）
连续 **3 次**脚本加载失败（`g_load_error_pending` 路径观测）→ 自动禁用该目标的模块加载，
宿主 UI 显示"因加载失败已暂停注入"并提供一键恢复。
理由：单脚本架构下坏脚本会让**每次**目标启动都受影响，必须能自愈。

### D13 — 刻意不改的东西（本批）
`AIDL`（继续用 `loadScript(String, Boolean)`）、`cpp/gumjs_bridge.cpp` 的结构（仅允许 pending 条目加 `moduleId` 字段）、
`provider/ScriptConfigProvider.kt` 的鉴权三件套（沿用）、`HookRouter` 的幂等键语义（`cls#method#sig#tag`，键值内嵌 moduleId）。

### D14 — 早期日志缓冲
引擎就绪但 `logReceiver` 未注册期间的日志（`onPackageReady`~Application 阶段）写入进程内 ring buffer（上限 500 行），
握手成功后一次性补发宿主 UI，避免"早期注入过程无日志可查"。

---

### D15 — remote prefs group 分离（三查后新增，解决 D5 隐患）

```
NOW（隐患）:
  lspfrifa_config                      ← 全部脚本挤在一个 group
    enabled      : StringSet(pkg)
    script.<pkg> : String
    hint_inject  : Boolean

AFTER（分离）:
  lspfrifa_config                      ← 只放小数据，永不放大脚本
    enabled           : StringSet(pkg)
    hint_inject       : Boolean
    scriptgroup.<pkg> : String         ← 指针：该包的专属 group 名
  lspfrifa_s_<pkgSafe>                 ← 每包一个，只含该包数据
    code      : String                 ← 脚本正文（大）
    modules   : StringSet(moduleId)
```

- pkgSafe = pkg.replace('.', '_').replace('-', '_')（包名字符集安全，无需 hash）
- **兼容**：模块侧先读 scriptgroup.<pkg> 指针；无指针则回退读旧的 script.<pkg>；再失败才走 Provider。
- **收益**：写任一目标的脚本只搬运该目标一个 group；enabled 集合的读写永不携带脚本正文。
- **代价**：读路径多一次 Binder 往返；写路径 2 次（指针仅首次写入）。

### D16 — 三查新增结论：onPackageReady 无需 Context 即可完成注入

官方示例 libxposed_example_ModuleMainKt.kt:50-78 实核确认：在 onPackageReady 内可直接调用
getRemotePreferences("test")、openRemoteFile("test.txt")、Class.forName(..., param.classLoader)、
hook(...)、setPriority(PRIORITY_HIGHEST)。
→ **工作流 3（提前注入）的可行性由官方示例直接背书，无需 Context。**
我此前标注的 R2 风险（早阶段无 Context）仅影响 Toast / 日志上 UI / register_ipc 三项，不影响引擎与 hook 挂载。

## 2. 冻结的存储键约定

```
# ===== 模块库（宿主侧，新增 data/ScriptLibraryStore.kt，SharedPreferences "lspfrifa_library"）=====
lib.index                  : StringSet(moduleId)
lib.<moduleId>.name        : String            # 显示名（默认取文件名）
lib.<moduleId>.code        : String            # 源码
lib.<moduleId>.origin      : String            # "file:<uri>" | "share:<pkg>" | "clip" | "manual" | "url:<u>(预留)"
lib.<moduleId>.addedAt     : Long
lib.<moduleId>.sha256      : String            # 去重键

# ===== 目标绑定（宿主侧本地 SharedPreferences，兼容兼容） =====
modules.<pkg>              : StringSet(moduleId)
module.<pkg>.<moduleId>.enabled : Boolean
script.<pkg>.bak.<ts>      : String            # 导入前自动备份（撤销用）
script.<pkg>.failCount     : Int               # D12 熔断计数

# ===== framework remote prefs（group 分离，D15）=====
# group "lspfrifa_config"（只放小数据）
enabled                    : StringSet(pkg)    # 既有，不动
hint_inject                : Boolean           # 既有，不动
scriptgroup.<pkg>          : String            # 新增：指针 → 专属 group 名
# group "lspfrifa_s_<pkgSafe>"（每包一个，只装该包）
code                       : String            # 脚本正文（模块拼装产物）
modules                    : StringSet(moduleId)   # 该包启用的模块集

# ===== 兼容读取顺序（模块侧） =====
1) scriptgroup.<pkg> 指针 → lspfrifa_s_<pkgSafe>.code
2) 旧键 script.<pkg>（无指针时回退，升级前数据）
3) Provider get_script（两通道都不可用）
```

注入侧拼装顺序（不变）：`LSP_SHIM_BASE` → `JavaBridgeBundle` → `LSP_SHIM_JAVA` → 各模块 IIFE（按 moduleId 排序稳定）。

> ⚠️ 写入纪律：`enabled` / `hint_inject` 位置不变（向后兼容）；所有写操作先判断 group 序列化尺寸，超 700KB 拒绝并报错。

## 3. 交付顺序（4 个工作流）

| # | 工作流 | 关键动作 | 风险 | 依赖 |
|---|---|---|---|---|
| 1 | **C — 解耦 Provider** | remote prefs 读启用/脚本/模块集；Provider 降兜底；早期日志 ring buffer（D14） | 低 | 无 |
| 2 | **导入 + 模块化** | `ScriptLibraryStore` + `ScriptImport` + 编辑器/详情页 UI + 库管理页；模块粒度 D9、冲突 D10、熔断 D12 | 低-中 | 1（键约定） |
| 3 | **A — 时机提前** | `onPackageReady` 实现 + 后台线程 + 后台引擎加载 + 有界同步 flush（D2） | 中 | 1 |
| 4 | **D — 类加载感知** | 三级再武装（D3）；激进模式设置开关 | 中-高 | 3 |

**纪律**：每个工作流先跑 `tools/check-kt.sh`（双绿：0 规则 + 0 未导入符号），再交用户编译。

---

## 4. 三查结果（已全部完成 — 2026-08-26）

| # | 项 | 结果 | 依据 |
|---|---|---|---|
| 1 | `onPackageReady` + `PackageReadyParam.getClassLoader()` | **已实核，存在且可用** | `XposedModuleInterface.java:84-99, 221` |
| 2 | 模块共享目录 API | **已实核，但仅为只读**：`String[] listRemoteFiles()` + `ParcelFileDescriptor openRemoteFile(String)`；宿主侧 `XposedService` 仅 `listRemoteFiles/openRemoteFile/deleteRemoteFile`，**无写入 API**（穷举 classes.jar 全部类） | `XposedInterface.java:546-564`；`libxposed-service-102.0.0.aar / classes.jar` |
| 3 | `HookBuilder.setPriority` | **已实核**：`HookBuilder setPriority(int priority)` | `XposedInterface.java:362`；示例 `ModuleMainKt.kt:110` |
| 4 | MiuixIcons 导入类图标 | **仍未核**（Miuix sources jar 不在本机） | 需用户侧提供或改用已用过的图标（`MiuixIcons.Edit`/`ListView` 已验证可用） |
| 5 | 脚本大小实际上限 | **推理已升级**：限制是 group 级非脚本级（见 D5）；**数值仍需实测** | `RemotePreferences/Editor` 反编译 |
| 6 | `XposedModule` 是否暴露 Context | **已实核：不暴露**（`XposedModule` 仅继承 `XposedInterfaceWrapper`，无 Context 方法）→ 但官方示例证明**注入本身不需 Context**（D16） | `XposedModule.java:8`；`ModuleMainKt.kt:50-78` |
| 7 | remote prefs 读写是否走 Binder | **已实核：是**（读 `requestRemotePreferences` / 写 `updateRemotePreferences`，每次搬运整个 group） | `RemotePreferences.java`、`RemotePreferences$Editor.java` |
| 8 | frida 多 script 是否共用 JS 线程（D11 依据） | **未实测**（依据 frida 源码模型，非本机实测） | 若要坐实需 1 次多 script PoC |

**新增第二条硬约束（重要）**：项目纪律的"API 未核对不得编码"已适用于上表；本批 6/8 项已核完，
剩余未核项（#4 图标、#5 数值、#8 线程模型）各自的处置：
- #4 → 用已验证的 `MiuixIcons.Edit` 先实现，图标美化归 UI 二轮
- #5 → 按 D5 实测协议实测后回填
- #8 → 不影响工作流 1/2/3；仅影响 D11 的书面依据强度（已在 §5 标注）

## 5. 已知限制（不可解，如实记录）

1. 模块内死循环 → 僵死 JS 线程 → 全部模块的 replace 退化为 observe（单线程事件循环，无抢占）。
2. 对象参数/返回值仍为 `__obj` 占位（B1/B2 既定边界）。
3. 部分 hook 时机硬性不可达：类加载前不存在 ArtMethod；D 档把窗口缩到"类加载瞬间"，不等于零延迟。
4. devkit QuickJS 无 timer → 所有定时/心跳类逻辑必须在 Kotlin/Java 侧。
5. **远程文件通道只写不了**：宿主无法向目标进程推送文件，所以脚本分发完全依赖 remote prefs（受 group 1MB 上限约束，见 D5/D15）。
6. **remote prefs 无事务语义**：`Editor.apply()` 失败仅打日志（`Failed to commit changes to framework`），调用方无从得知——写入必须靠读回验证。

---


### 第三轮交付（D12 熔断闭环 + 库管理页）

#### D12 熔断（本轮从死代码补成闭环）
设计要点：**目标进程无法自写状态**（remote prefs 在被 hook 的 app 里只读），故：
计数由宿主维护、**闸写进 remote prefs**（目标进程唯一能读到的持久状态）、
register_ipc 会拉起宿主进程因此宿主通常在场。

| 环节 | 实现 |
|---|---|
| 目标侧读闸 | `LSPFRIFAModule.runInitChain` 入口先查 `circuitOpen.<pkg>`，闸断则 `event=circuit_open_skip` 直接 return（不再尝试注入） |
| 目标侧上报 | 三处脚本加载失败分支（remote_group / legacy / provider）调 `reportLoadFailure()` → Provider `report_load_failure` |
| 宿主侧计数 | `IpcManager.reportLoadFailure` → `bumpFailCount`，达 3 次即 `openCircuit` |
| 用户合闸 | 详情页熔断卡「恢复注入」→ `IpcManager.closeCircuit`（清计数 + 删闸） |
| 鉴权 | `report_load_failure` 走 F1 的 **caller-arg uid 匹配**（沿用既有机制，未新开洞） |

#### 库管理页
新增 `ui/screen/ScriptLibraryScreen.kt`（216 行）：列表 / 应用 / 删除 / 空态。
入口在项目详情页「脚本库」行；带 targetPackage 传入，因此可**直接应用到当前目标**
（复用与导入一致的安全顺序：备份旧脚本 → 写新脚本 → 四态反馈）。

#### 改动文件
| 文件 | 改动 |
|---|---|
| `ipc/ScriptStore.kt` | 新增熔断闸 API：`CIRCUIT_THRESHOLD` / `openCircuit` / `closeCircuit` / `isCircuitOpen` |
| `ipc/IpcManager.kt` | 新增 `reportLoadFailure` / `isCircuitOpen` / `closeCircuit` |
| `provider/ScriptConfigProvider.kt` | 新增 `report_load_failure` 方法 |
| `xposed/LSPFRIFAModule.kt` | `runInitChain` 闸门；`reportLoadFailure`；三处失败分支上报 |
| `ui/screen/ProjectDetailScreenV093.kt` | 熔断状态轮询 + 熔断卡（含「恢复注入」）+ 脚本库入口行 |
| `ui/screen/ScriptLibraryScreen.kt` | 新增 |
| `MainActivity.kt` | 新增 `script_library/{pkg}?name={name}` 路由 |

#### 验证
- `tools/check-kt.sh` **双绿**
- 花括号净值：MainActivity 93/93、ScriptLibraryScreen 39/39、ProjectDetail 63/63 —— 全部平衡
- 路由共 7 条（main / select_project / project_detail / script_editor / logs / script_library / import_target），嵌套逐行核对无误

### 仍未交付
- ~~工作流 3~~ ✅；~~工作流 4（类加载感知）~~ ✅ `f069e7f`（代码层面；编译与真机未验证）
- 模块化拼装（`modules.<pkg>` 已建键但未接入注入链）——用户已明确暂缓，`setModules`/`enabledModules` 暂为未调用
- **D5 尺寸阀值真机实测**（200KB / 500KB / 900KB / 1.2MB）——需用户侧
- 熔断链路的真机验证（本轮为静态实现，未跑过真机）


### 第四轮交付（工作流 3：onPackageReady 提前注入）

#### 实现思路（第一性原理）
注入链四段里，**只有“宿主协同”需要 Context**：
| 段 | 需要的依赖 | 早期阶段能否做 |
|---|---|---|
| ① 引擎准备 | 进程存在 | ✅ |
| ② hook 就绪 | app classloader | ✅（param.classLoader） |
| ③ 宿主协同（握手/日志上 UI/Toast） | Context | ❌ 留 Application 阶段 |
| ④ 启用判定 | remote prefs | ✅（Provider 需要 Context，跳过） |
因此提前阶段 = ①②④，剩下的 ③ 交回 Application 阶段。

#### 关键实现点
| 点 | 处理 | 为何 |
|---|---|---|
| classloader 源 | 用 `param.classLoader`，**不用** `getDefaultClassLoader()` | ACF 可能换 CL，用错 → 全部 hook 静默 MISS |
| 主线程纪律 | 整个早期流程丢守护线程 `lspfrifa-early` | F2 教训：不得阻塞框架回调所在的目标主线程 |
| 路由接管 | Application 阶段若有 earlyRouter 则 `attachRuntime(app) {...}` 复用，不重建 | 重建会丢失已挂 hook 手柄 → 泄漏 + 关不掉 |
| 重载防护 | 仅 `earlyScriptLoaded`（**真载成功**）才跳过 App 阶段的 loadInitialScript | 重载会 unload+recreate，清掉 Interceptor 制造失效窗口 |
| 回滚开关 | `EARLY_INJECT_ENABLED` 编译期常量 | 早期挂载与目标启动并行存在时序竞争，需一个不用重新设计的退回路径 |

#### 交付中发现并修复的自身 bug（重要）
初版用**一个**集合 `earlyInited` 同时表达“尝试过”与“成功过”，导致三种失败路径全部退化为“永不注入”：
1. 引擎加载抛错 → App 阶段跳过载脚本
2. 早期读不到脚本（`early_no_script`）→ App 阶段的 Provider 回退被绕过
3. 早期 `enabled != true`（无 remote 配置）→ 三态判定 + 延迟重试全被绕过

**修复**：拆为 `earlyAttempted`（防重入）与 `earlyScriptLoaded`（真载成功才置位，专供跳过判断），
`loadScriptEarly` 改为返回 Boolean。

#### 改动文件
| 文件 | 改动 |
|---|---|
| `xposed/LSPFRIFAModule.kt` | 新增 `onPackageReady` / `runEarlyInject` / `loadScriptEarly`；两个集合；`earlyRouter` 字段；`runInitChain` 接管与跳过逻辑；顺手删除误加的 `ContentValues` import，修复被挤掉的 `TargetCheck` KDoc |
| `xposed/HookRouter.kt` | `appContext`/`hostLog` 改为 `private var`；新增 `attachRuntime(context, log)`（注意 `this.` 防参数遮蔽） |

#### 验证
- `tools/check-kt.sh` 双绿
- `LSPFRIFAModule.kt` 花括号 138/138；无旧集合残留
- 新增日志（供真机验证）：`early_router_ready` / `early_script_loaded` / `early_no_script` / `early_check_skip` / `early_circuit_open_skip` / `early_router_taken_over` / `skip_reload_early_loaded`

### 仍未交付
- 模块化拼装（已暂缓）
- 所有真机验证（含本轮提前注入、D5 尺寸阀值、熔断链路）

### 第五轮交付（工作流 4：类加载感知 / 三级再武装）

commit `f069e7f`（5 文件 +347/-1）。解决了设计之初就登记的硬限制：
**LSPlant 要求 ArtMethod 已存在，类未加载时 hook 请求只能 MISS**。

#### 三级实现
| 级 | 触发 | 成本 | 状态 |
|---|---|---|---|
| ① 锚点 flush | `onPackageReady` 路由就绪后；`callApplicationOnCreate` 拦截内、proceed 之前 | 队列空时≈0 | 代码到，未真机验证 |
| ② Kotlin 轮询 | 队列非空起守护线程，200ms；队列空即退出；上限 600 tick（≈2min） | 仅队列非空时 | 代码到，未真机验证 |
| ③ loadClass 监听 | hook `ClassLoader.loadClass(String,boolean)`，proceed **之后**处理 | 稳态=一次 isEmpty() | 激进模式，**默认关**，设置项可开 |

#### 五条安全纪律（逐条对应实现，均来自本轮自查）
1. **轮询线程必须全包异常**：安卓任意线程未捕获异常会杀掉**整个目标进程**。
   → `try/catch(Throwable)/finally`，绝不外抛（`POLL_ERR` 日志）。
2. **绝不允许双重出队**：`ConcurrentHashMap` 迭代器连续两次 `remove()`（中间无 `next()`）抛 ISE，
   而其中一条路径暴露在目标主线程上 → `flushPending` 重构为两阶段（先解析，成功才出队）。
3. **loadClass 处理必须在 proceed 之后**，并经 `mainHandler.post` 投递：
   安装 hook 本身会加载类，与当前加载共用 per-loader 锁 —— 同步做等于自己抢自己的锁。
4. **`armOnClass` 必须 @Synchronized**：消息路径与 flush 路径可并发，
   同时通过 `containsKey` 检查会对同一方法挂两次，后一个 handle 覆盖前一个 → 泄漏且卸不掉。
5. **锚点预算保守**：`FLUSH_MAX_ITEMS` 16→8。每项可能触发 LSPlant 安装（含 deopt），
   主线程预算需保守；剩余交给②（非主线程，代价几乎为零）。

#### 实现中的额外修正
- `unhookByTag` 改用“先筛键再逐个 remove”，不依赖 `entrySet().removeIf` 实现细节。
- `unhookAll` 同步 `clearPending()` —— 防上一轮脚本的 hook 迟到生效。
- 类加载监听手柄**单独持有**（不进 `handles`）：它不是脚本注册的 hook，
  不应随脚本重载被 `unhookAll` 卸掉；必须强引用持住防 GC。

#### 新增文件
- `data/HookModeStore.kt`（54 行）：设置项存储，与 `InjectHintStore` 同构，
  写 `lspfrifa_config` 组下发（目标进程只读）。键 `loadclass_watch`，**默认关**。

#### 真机核验点（新增日志关键字）
`MISS_CLASS_QUEUED` / `ARMED_LATE src=anchor|poll|loadclass` / `POLL_GIVEUP` /
`POLL_ERR` / `LATE_ARM_ERR` / `PENDING_CLEARED` /
`CLASSLOADER_WATCH_ON|OFF|FAIL` / `loadclass_watch_requested`

#### ⚠️ 本轮的诚实声明
- **编译未验证**：最后一次成功构建在 07:32（W3 之前）；W4 代码（含新文件 HookModeStore）尚未编译过。
- **真机未验证**：三级逻辑一次都没在设备上跑过。
- `uninstallClassLoaderWatcher()` 已定义但**当前无调用方**（预留给“开关关闭后即时卸载”，
  但按 D13 不改 AIDL，运行期关闭只能下次注入生效，故暂未接）。

### 四个工作流全部完成（代码层面）
| 工作流 | 内容 | commit |
|---|---|---|
| W1 | D4 解耦 Provider + D15 remote prefs 分组 | `ab28c4d` 含 |
| W2 | D6 导入四通道 + D7 备份撤销 + D8/D9 脚本库 + D12 熔断 | `ab28c4d` 含 |
| W3 | onPackageReady 提前注入 | `ab28c4d` 含 |
| W4 | D3 类加载感知（三级再武装） | `f069e7f` |
| 编译警告修复 | err 解析语义 + componentType 类型安全 | `1cdddea` |

**模块化拼装仍暂缓**（用户明确）；`setModules`/`enabledModules` 仍未调用。

## 6. 验证清单（每工作流交付后由用户侧执行）

- **工作流 1**：宿主冷启动前先启动目标 → 脚本应仍注入成功（`load_persisted_script src=remote_prefs`）；
  `event=binder_handshake_deferred` 不应阻断注入。
- **工作流 2**：文件/剪贴板/分享三通道各导入一次；重复导入同内容不产生重复条目；撤销导入可回滚；
  两模块同方法 replace 时出现 `CONFLICT` 日志；连续 3 次坏脚本触发熔断且可一键恢复。
- **工作流 3**：`ARMED` 日志时间早于 `event=application_created`（或至少同阶段）；
  hook `Application.onCreate` 时命中率显著提升；目标冷启动无可见变慢。
- **工作流 4**：脚本直接 hook 一个未加载的应用类 → 不再 `MISS`，而在该类加载后自动 `ARMED`；
  激进模式关闭时行为退回工作流 3。

## 7. 实施记录（工作流 1 已交付 — 2026-08-26）

### 新增文件
| 文件 | 作用 |
|---|---|
| `data/ScriptLibraryStore.kt` | D8/D9 模块库（SharedPreferences `lspfrifa_library`；sha256 去重；增删改查） |
| `data/ScriptImport.kt` | D6/D7 导入解析（SAF Uri / 文本；二进制嗅探 / BOM / 括号配平；不阻断式警告） |
| `xposed/EarlyLogBuffer.kt` | D14 早期日志 ring buffer（500 行，flush 幂等） |

### 改动文件
| 文件 | 改动 |
|---|---|
| `ipc/ScriptStore.kt` | **重写**：D15 分组（`lspfrifa_config` 只放小数据 + `lspfrifa_s_<pkgSafe>` 专属正文）；`saveScript` 返回 `SaveResult` 四态；`commit()` 同步校验；备份/撤销；熔断计数；模块集 |
| `ipc/IpcManager.kt` | `pushScript`/`saveScript` 透传 `SaveResult`；新增 backup/undoImport/failCount/isRemoteAvailable |
| `provider/ScriptConfigProvider.kt` | `save_script` 返回 `result` 字段（不再只回 boolean） |
| `xposed/LSPFRIFAModule.kt` | D15 读取链（指针 → 专属 group → 旧键 → Provider）；`record()` 双写早期日志 |
| `xposed/TargetIpcServer.kt` | 握手成功时 `EarlyLogBuffer.flush`；`hostLog` 双写 buffer |
| `ui/screen/ScriptEditorScreenV1.kt` | 顶栏导入入口（MiuixIcons.Edit）；来源选择弹窗（文件/剪贴板/撤销）；预览确认弹窗；`pushScript` 适配四态反馈 |
| `LSPFRIFAApplication.kt` | `ScriptLibraryStore.init(this)` |

### 检查结果
`bash tools/check-kt.sh` → **两条全绿**（0 规则命中 + 0 未导入符号）。
唯一告警：`ScriptImport.kt` 花括号 30/29 —— **已知假阳性**（字符字面量 `LBRACE`/`RBRACE` 各计 1，工具不排除 char 字面量；项目坑位库已记录此规则缺陷）。

### 交付期间的自我纠正（如实记录）
1. 首次用 `apply_file` 行内注入弹窗时，因 `lastIndexOf('}')` 定位错误，把弹窗块插进了 `ImportPreviewDialog` 内部 → 改用 `git show HEAD:<file>` 取回干净基线（279 行）整体重建，不再做增量补丁。
2. 两次被 `tools/check-kt.sh` 拦住：`ArrayDeque` 未 import（真问题）；花括号（假阳性）。
3. 三查推翻了初版设计两处（见 D4/D5 修订）：文件通道只读（无写入 API）、remote prefs 限制是 group 级。


### 第二轮交付（同日，D6 分享入口闭环）

| 文件 | 类型 | 作用 |
|---|---|---|
| `data/PendingImport.kt` | 新增 | 分享进入的待处理单例。**在 Intent 到达当下就读完字节**（R8 风险的落地对策：Uri 授权会过期，不能只存 Uri） |
| `ui/screen/ImportTargetScreen.kt` | 新增 | 分享后“导到哪里”决策页：仅入库 / 应用到某个已添加目标（分享无目标上下文，必须问用户） |
| `AndroidManifest.xml` | 改动 | MainActivity 加 ACTION_SEND + ACTION_VIEW intent-filter（零新增权限） |
| `MainActivity.kt` | 改动 | `onCreate`/`onNewIntent` 捕获分享；新增 `import_target` 导航路由 |

### 验证结果（本轮结束）
- `tools/check-kt.sh` → **两条全绿**
- 逐文件花括号净值：EarlyLogBuffer 5/5、ScriptLibraryStore 15/15、ScriptImport 排除字面量后 **28/28**、PendingImport 11/11、ImportTargetScreen 27/27、ScriptStore 28/28、MainActivity 85/85 → **全部平衡**
- `ScriptImport.kt` 的 30/29 告警已定性：工具未排除字符字面量（`'{'`/`'}'`），**已知假阳性**，建议后续给 `check-kt.sh` 的括号规则加字符字面量排除

### 本轮 AI 自己的错误（如实记录，供后续警惕）
1. `apply_file` 参数名写错（`new_text` 应为 `new`）多次
2. 行内 JS 模板字符串与 Kotlin 的 `${...}` 插值冲突 → 改用数组拼接 + 文件落盘执行
3. **最严重**：用 `lastIndexOf('}')` 定位插入点，把弹窗块插进了另一个对话框内部，破坏文件结构 → 改用 `git show HEAD:<file>` 取干净基线整体重建，**教训：结构性插入不能用“最后一个括号”定位**
4. MainActivity 新路由插进了 logs composable 内部（同样原因），靠花括号净值 + 手工缩进核对才发现

> **给后续会话的硬建议**：本项目 Kotlin 编辑严禁“找最后一个 } 插入”；应先取 git 基线，再用唯一字符串锚点 + 完整块重建。本次已因此浪费两轮修复。

### 尚未交付（后续工作流）
- ~~工作流 2 余项~~：库管理页 ✅、D12 熔断 ✅（本轮完成）；模块化拼装 ⏸️（用户暂缓）；D10 冲突语义 ⏸️（依赖模块化）
- 工作流 3：`onPackageReady` 提前注入
- 工作流 4：类加载感知
- ~~分享入口（`ACTION_SEND` intent-filter）~~ ✅ 本第二轮已闭环（PendingImport + ImportTargetScreen + Manifest + 路由）
- **实测回填**：D5 的四个尺寸阈值（200KB/500KB/900KB/1.2MB）需用户侧真机验证

