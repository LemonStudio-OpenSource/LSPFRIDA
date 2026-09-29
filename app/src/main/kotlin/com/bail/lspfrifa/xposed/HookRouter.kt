package com.bail.lspfrifa.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 官方通道路由（P0）——主修复方向 + RouteB B1 replace 语义。
 *
 * 背景：frida-java-bridge 的 Java.use().implementation 在 Android 15 / ART 35 上静默失效
 * （上游 frida-java-bridge#334 open）。libxposed 的 hook() = 框架自带 LSPlant 引擎（官方在
 * A12~A16 全量验证）。因此业务 Java hook 一律转接 libxposed hook()，GumJS 仅做业务层与
 * 进程内 hook 请求转发（send 消息 → on_message → 本路由）。
 *
 * 链路（observe，与现状零差异）：
 *   JS: LSP.hook(cls, method, tag) → send → on_message → 本路由 tryHandle()
 *   → Class.forName + 反射取方法 → hook(executable).intercept(Hooker) → 命中 hostLog。
 *
 * 链路（RouteB B1 replace，t7）：
 *   JS: Java.use("x.y").m.implementation = fn → send({t:"lsp.hook", mode:"replace", tag:"x.y#m"})
 *   → 本路由按 mode 建 replace intercept：
 *      chain.getArgs() 编码 → GumJsBridge.callJs(id, payload)（cpp 登记 pending + frida:rpc post）
 *      → JS rpc.exports.__lspHookReply(id, argsJson) 执行用户 fn → dispatcher 自动标准 reply
 *      → cpp on_message 消费 reply（命中 pending 才回传 Kotlin，其余静默）→ callJs 返回：
 *        ""                → 超时/异常 → chain.proceed()（原参，原方法必执行——目标不崩）
 *        {"__inner":true[,"args":[...]]} → async fn 内 await this.method(...) 原调用请求（B2：args 为
 *                            自定义参数，按 parameterTypes 转换后 proceed(convArgs)；无 args/不可转换
 *                            → 安全回退原参 proceed()）→ nativePostOriginalReply
 *                            → 再次 callJs(id,"") 续等最终 reply（同 id 栈复用，最多 500ms/阶段）
 *        {"over":false}    → fn 返回 undefined（观察语义）→ chain.proceed() 返回原值
 *        {"over":true,"r":…} → 解码 r（按 executable 返回类型转换）→ return r（跳过原方法）
 *
 * B1/B2 已定案（Facts §5 / cap 裁定）：对象参数/返回为占位/透传（CAST_FAIL 安全回退原参）；
 * 同步 fn 内 this.method → shim 抛可读错误 → error reply → 按 over=false 处理。
 * B2（t15）：overload('I','java.lang.String') 精确选择（sigs 过滤）+ this.method 自定义参数。
 */
class HookRouter(
    private val targetPackage: String,
    private val targetLoader: ClassLoader,
    appContext: Context? = null,
    private val hooker: (Executable) -> XposedInterface.HookBuilder,
    hostLog: (String) -> Unit,
) {
    /**
     * D2：这两个依赖在提前阶段拿不到（无 Context），因此可在 Application 阶段经
     * [attachRuntime] 补上。其余字段（loader/hooker）提前阶段就有，保持 val。
     */
    @Volatile
    private var appContext: Context? = appContext

    @Volatile
    private var hostLog: (String) -> Unit = hostLog

    /**
     * D2：接管时注入运行期依赖（Application 上下文 + 日志上行通道）。
     * 专为“onPackageReady 先建、Application 阶段接管”设计，只补缺不重建 ——
     * 重建会丢掉已挂的 hook 手柄。
     */
    fun attachRuntime(context: Context?, log: (String) -> Unit) {
        // 注意：参数名不能与属性同名，否则赋值会砸到参数上（Kotlin 参数不可变 = 编译错误）
        this.appContext = context
        this.hostLog = log
    }

    // ==================== D3：类加载感知（三级再武装） ====================

    /** D3：入待挂队列，并确保轮询在跑（幂等）。 */
    private fun enqueuePending(req: HookRequest) {
        val key = req.clsName + "#" + req.methodName + "#" + req.tag
        val isNew = pendingHooks.putIfAbsent(key, req) == null
        hostLog(
            "[lsp-hook] MISS_CLASS_QUEUED ${req.clsName}#${req.methodName} " +
                (if (isNew) "(queued)" else "(already queued)") +
                " pending=" + pendingHooks.size
        )
        ensurePolling()
    }

    /**
     * D3②：启动轮询（幂等）。
     * 为何不用 Handler.postDelayed：本类可能运行在任何线程，handler 常驻会增加状态；
     * 一个"队列空就退出"的守护线程语义更简单、更易推理。
     */
    @Synchronized
    private fun ensurePolling() {
        val cur = pollThread
        if (cur != null && cur.isAlive) return
        val th = Thread {
            var ticks = 0
            try {
                while (ticks < POLL_MAX_TICKS) {
                    if (pendingHooks.isEmpty()) break
                    try {
                        Thread.sleep(POLL_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    ticks++
                    // 逐次兜底：单次 flush 出错不应终止轮询
                    runCatching { flushPending(Int.MAX_VALUE, "poll") }
                }
                if (pendingHooks.isNotEmpty()) {
                    hostLog(
                        "[lsp-hook] POLL_GIVEUP pending=" + pendingHooks.size +
                            " ticks=" + ticks + " (类始终未加载；等下次脚本重跑)"
                    )
                }
            } catch (t: Throwable) {
                // ★ 安卓：任意线程的未捕获异常都会由默认处理器杀掉**整个应用进程**。
                //   本线程运行在目标 App 内，因此必须把所有异常拦在此处，绝不外抛。
                runCatching { hostLog("[lsp-hook] POLL_ERR " + t.message) }
            } finally {
                pollThread = null
            }
        }
        th.isDaemon = true
        th.name = "lspfrifa-hook-poll"
        pollThread = th
        th.start()
    }

    /**
     * D3①：锚点 flush —— 在已知的早期时机（callApplicationOnCreate 拦截内、proceed 之前）
     * 尝试挂载一批待挂请求。
     * @return 已成功挂上的条数（0 = 无进展，调用方可据此决定是否继续）
     */
    fun flushAtAnchor(): Int {
        if (pendingHooks.isEmpty()) return 0
        return flushPending(FLUSH_MAX_ITEMS, "anchor")
    }

    /**
     * 逐条尝试重挂（类可解析则挂上并出队）。
     * @param limit 本轮处理上限（锚点路径必须可估界，防目标主线程卡顿）
     * @param source 日志来源标记（anchor / poll）
     */
    private fun flushPending(limit: Int, source: String): Int {
        if (pendingHooks.isEmpty()) return 0
        var done = 0
        val it = pendingHooks.entries.iterator()
        while (it.hasNext() && done < limit) {
            val req = it.next().value

            // 第一阶段：解析类（决定“出队”还是“留队”）。
            // 用 null 作哨兵值，而非在 catch 里 continue —— 后者在“try 作表达式”的场景下
            // 对 Kotlin 版本有兼容不确定性，而此处无法编译验证，故取无争议写法。
            var resolved: Class<*>? = null
            try {
                resolved = Class.forName(req.clsName, false, targetLoader)
            } catch (_: ClassNotFoundException) {
                // 仍未加载：留在队列等下一轮（预期路径，不打日志避免刷屏）
            } catch (t: Throwable) {
                // 解析期其它错误（如 NoClassDefFoundError）：出队防死循环
                it.remove()
                hostLog("[lsp-hook] LATE_ARM_ERR ${req.clsName}#${req.methodName} err=" + t.message)
            }
            val clazz = resolved
            if (clazz == null) continue

            // 第二阶段：出队 + 挂载。
            // remove() 只在此处调用一次 —— ConcurrentHashMap 迭代器连续两次 remove()
            // （中间无 next()）会抛 IllegalStateException，而其中一条路径暴露在目标主线程上，
            // 故绝不允许双重出队。
            it.remove()
            try {
                armOnClass(req, clazz)
                done++
                hostLog("[lsp-hook] ARMED_LATE ${req.clsName}#${req.methodName} src=$source")
            } catch (t: Throwable) {
                // 已在上面出队，这里不再 remove（防 ISE）
                hostLog("[lsp-hook] LATE_ARM_ERR ${req.clsName}#${req.methodName} err=" + t.message)
            }
        }
        return done
    }

    /** 当前待挂数（诊断/UI 用）。 */
    fun pendingCount(): Int = pendingHooks.size

    /** D3：卸载时清队列（脚本重跑时旧请求已无意义）。 */
    private fun clearPending() {
        if (pendingHooks.isNotEmpty()) {
            hostLog("[lsp-hook] PENDING_CLEARED n=" + pendingHooks.size)
        }
        pendingHooks.clear()
    }

    /**
     * D3③：安装类加载监听 —— 把“类加载后多久能挂上”从轮询的 200ms 降到下一个消息循环。
     *
     * **激进模式**：hook 的是 ClassLoader.loadClass(String, boolean)，JVM 里最热的路径之一，
     * 因此默认不装，由宿主设置显式开启（remote prefs "loadclass_watch"）。
     *
     * 三条安全纪律（缺一不可）：
     *  1. **proceed 之后才处理**：绝不在类加载路径中同步安装 hook ——
     *     安装过程本身会加载类，与当前加载共用 per-loader 锁，同步做等于自己抢自己的锁（可死锁）。
     *  2. **经 mainHandler.post 投递**：彻底离开类加载锁域再动手，代价仅几十毫秒延迟（远优于轮询 200ms）。
     *  3. **先判空再扫描**：稳态（队列空）下本路径的开销就是一次 isEmpty()，可忽略。
     *
     * 幂等：重复调用只装一次；失败时重置标志以便下次重试。
     */
    fun installClassLoaderWatcher() {
        if (classLoaderWatcherInstalled) return
        try {
            val m = ClassLoader::class.java.getDeclaredMethod(
                "loadClass",
                String::class.java,
                java.lang.Boolean.TYPE,
            )
            classLoaderWatcherHandle = hooker.invoke(m).intercept { chain ->
                val name = runCatching { chain.getArg(0) as? String }.getOrNull()
                // 完全放行：不改参数、不改返回值、不吞异常（loadClass 抛 CNFE 时自然上抛）
                val loaded = chain.proceed()
                if (name != null && !pendingHooks.isEmpty()) {
                    // 队列非空才做这次扫描；命中才投递（避免每次类加载都排一个消息）
                    val relevant = pendingHooks.values.any { it.clsName == name }
                    if (relevant) {
                        mainHandler.post {
                            runCatching { flushPending(Int.MAX_VALUE, "loadclass") }
                        }
                    }
                }
                loaded
            }
            classLoaderWatcherInstalled = true
            hostLog("[lsp-hook] CLASSLOADER_WATCH_ON")
        } catch (t: Throwable) {
            classLoaderWatcherInstalled = false
            hostLog("[lsp-hook] CLASSLOADER_WATCH_FAIL err=" + t.message)
        }
    }

    /** D3③：卸载类加载监听（目前供设置关闭/诊断用；脚本重载不调用它）。 */
    fun uninstallClassLoaderWatcher() {
        runCatching { classLoaderWatcherHandle?.unhook() }
        classLoaderWatcherHandle = null
        classLoaderWatcherInstalled = false
        hostLog("[lsp-hook] CLASSLOADER_WATCH_OFF")
    }

    private companion object {
        /**
         * D3②：轮询间隔。与设计文档 D3 一致（200ms）。
         * 为何调度必须在 Kotlin/Java 侧：devkit QuickJS 未实现 setInterval/setTimeout（已实证）。
         */
        const val POLL_INTERVAL_MS = 200L

        /** D3②：轮询上限 ≈ 2 分钟（600 × 200ms）。超时放弃并日志，防线程永久驻留。 */
        const val POLL_MAX_TICKS = 600

        /**
         * 单次锚点 flush 的处理上限。
         *
         * 为何是 8 而不是更大：锚点 flush 跑在目标**主线程**（callApplicationOnCreate 拦截内、
         * proceed 之前），每一项都可能触发 LSPlant 安装（含 deopt），必须给主线程留预算；
         * 剩下的条目由轮询线程（非主线程，200ms 后接手）继续处理，代价几乎为零。
         * 若真机观察到启动卡顿，继续下调此值即可（无需改结构）。
         */
        const val FLUSH_MAX_ITEMS = 8
    }

    private data class HookRequest(
        val clsName: String,
        val methodName: String,
        val tag: String,
        val act: String,
        val mode: String,
        /** B2：overload 精确选择（参数类型数组，如 ["I","java.lang.String"]）；null=挂全部 */
        val sigs: List<String>? = null,
    )

    /** 已挂 handle（key = cls#method#sig#tag；t7 修复 t2 P0#1：签名入键，overload 不再互相覆盖/泄漏） */
    private val handles = ConcurrentHashMap<String, XposedInterface.HookHandle>()

    /**
     * D3：待挂队列 —— 类尚未被加载时的 hook 请求（原逻辑直接 MISS 丢弃）。
     *
     * 为何需要：LSPlant 要求 ArtMethod 已存在，而脚本常在目标类加载前就跑；
     * 尤其是提前注入（W3）后脚本时机更早，MISS 概率也随之上升。
     *
     * 去重维度用 cls#method#tag（**不含签名**）：同一语义的请求重复入队无意义，
     * 真正多 overload 的区分在 armOnClass 里靠 sigs 完成。
     */
    private val pendingHooks = ConcurrentHashMap<String, HookRequest>()

    /** D3②：轮询线程只允许一个；队列空即退出。 */
    @Volatile
    private var pollThread: Thread? = null

    /** D3③：类加载监听是否已安装（幂等守卫）。 */
    @Volatile
    private var classLoaderWatcherInstalled = false

    /**
     * D3③：监听手柄。单独持有、不进 handles：
     *  1. 它不是脚本注册的 hook，不应随脚本重载被 unhookAll 卸掉；
     *  2. 必须强引用持住 —— 丢了引用等于给 GC 开绿灯（框架侧未必保活）。
     */
    @Volatile
    private var classLoaderWatcherHandle: XposedInterface.HookHandle? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** replace 全局串行互斥（B1 冻结）：一次一个在途 replace 请求；并发命中排队（≤500ms/个） */
    private val replaceLock = Any()

    private val reqSeq = AtomicLong(0)

    private fun buildKey(req: HookRequest, sig: String) = "${req.clsName}#${req.methodName}#$sig#${req.tag}"

    private fun methodSig(m: Executable): String = m.toString()

    /**
     * 处理一条来自 GumJS 上行的消息。
     * @return true = 消息已被本路由消费（不再上行原始 JSON 到宿主 UI）。
     */
    fun tryHandle(message: String): Boolean {
        val req = parseHook(message)
        if (req != null) {
            handle(req)
            return true
        }
        if (isUnhookAll(message)) {
            unhookAll()
            return true
        }
        if (parseUnhook(message)) {
            return true
        }
        return handleToastMessage(message)
    }

    /** P1：t="lsp.unhook_all" 消息判断（脚本热更前批量卸载 LSPlant 手柄）。 */
    private fun isUnhookAll(message: String): Boolean {
        return try {
            val outer = JSONObject(message)
            if (outer.optString("type") != "send") return false
            outer.optJSONObject("payload")?.optString("t") == "lsp.unhook_all"
        } catch (_: Throwable) {
            false
        }
    }

    /** RouteB：t="lsp.unhook"（JS 侧 implementation=null / 重复赋值前先卸）→ 按 tag 卸全部 overload 手柄。 */
    private fun parseUnhook(message: String): Boolean {
        return try {
            val outer = JSONObject(message)
            if (outer.optString("type") != "send") return false
            val payload = outer.optJSONObject("payload") ?: return false
            if (payload.optString("t") != "lsp.unhook") return false
            val tag = payload.optString("tag")
            if (tag.isEmpty()) return false
            unhookByTag(tag)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun unhookByTag(tag: String) {
        // D3：同 tag 的待挂项也该撤（否则“卸了又被迟到挂上”语义矛盾）。
        // 用“先筛键再逐个 remove”，不依赖 entrySet().removeIf 的容器实现细节。
        pendingHooks.filterValues { it.tag == tag }.keys.toList()
            .forEach { k -> pendingHooks.remove(k) }
        val matched = handles.keys.filter { it.endsWith("#$tag") }
        matched.forEach { key ->
            handles.remove(key)?.let { h -> runCatching { h.unhook() } }
        }
        if (matched.isNotEmpty()) hostLog("[lsp-hook] UNHOOKED tag=$tag count=${matched.size}")
    }

    /**
     * P1：卸载全部已注册 LSPlant 手柄（HookHandle.unhook 框架 API，幂等）。
     * 用于脚本热更：重载前不清理旧 hook，会导致同方法重复拦截语义残留。
     */
    fun unhookAll() {
        // D3：脚本重跑时旧待挂请求已无意义 —— 一并清掉，防止“上一轮脚本的 hook 迟到生效”
        clearPending()
        val n = handles.size
        handles.values.forEach { h -> runCatching { h.unhook() } }
        handles.clear()
        hostLog("[lsp-hook] UNHOOKED all=$n")
    }

    /** 解析 Frida 消息协议 {type:"send", payload:{t:"lsp.hook",...}}；非本类消息返回 null。 */
    private fun parseHook(message: String): HookRequest? {
        return try {
            val outer = JSONObject(message)
            if (outer.optString("type") != "send") return null
            val payload = outer.optJSONObject("payload") ?: return null
            if (payload.optString("t") != "lsp.hook") return null
            val cls = payload.optString("cls")
            val method = payload.optString("method")
            if (cls.isEmpty() || method.isEmpty()) return null
            val sigsArr = payload.optJSONArray("sigs")
            HookRequest(
                clsName = cls,
                methodName = method,
                tag = payload.optString("tag"),
                act = payload.optString("act"),
                mode = payload.optString("mode"),
                sigs = if (sigsArr == null) null else (0 until sigsArr.length()).map { sigsArr.getString(it) },
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 直击弹窗（无 hook 依赖的链路诊断）：解析 {type:"send", payload:{t:"lsp.toast", msg}}，
     * post 到目标进程主线程用应用 Context 弹出。返回 true=已消费。
     */
    private fun handleToastMessage(message: String): Boolean {
        val msg = try {
            val outer = JSONObject(message)
            if (outer.optString("type") != "send") return false
            val payload = outer.optJSONObject("payload") ?: return false
            if (payload.optString("t") != "lsp.toast") return false
            payload.optString("msg").takeIf { it.isNotEmpty() } ?: return false
        } catch (_: Throwable) {
            return false
        }
        // 提前注入阶段 appContext 为 null（Context 要到 App 阶段才由 attachRuntime 补上）。
        // 此时不能 return true（那会把消息“消费掉”，宿主 UI 永远看不到这次请求），
        // 而要返回 false 让调用方继续走未消费分支（early 回调会入 EarlyLogBuffer 补发）。
        val ctx = appContext ?: run {
            hostLog("[lsp-toast] deferred (no context yet) msg=$msg")
            return false
        }
        hostLog("[lsp-toast] requested msg=$msg")
        mainHandler.post {
            runCatching {
                Toast.makeText(ctx, "[lsp-toast] $msg", Toast.LENGTH_LONG).show()
            }
        }
        return true
    }

    private fun handle(req: HookRequest) {
        // 类必须已被目标进程加载才能挂（LSPlant 不支持未加载类自动延迟挂，P0 不做）；
        // framework 类（android.*）经 targetLoader 可 delegate 到 boot classloader。
        val clazz = try {
            Class.forName(req.clsName, false, targetLoader)
        } catch (_: ClassNotFoundException) {
            // D3：不再直接丢弃 —— 入待挂队列，等类可解析后再挂（锚点 flush / Kotlin 轮询）
            enqueuePending(req)
            return
        } catch (t: Throwable) {
            hostLog("[lsp-hook] MISS_ERR class=${req.clsName} err=${t.message}")
            return
        }
        armOnClass(req, clazz)
    }

    /**
     * D3：真正的挂载路径（类已可解析）。由 handle 与 flushPending 共用。
     *
     * @Synchronized 的必要性：调用方有两条并发路径 —— 消息路径（handle）与
     * flush 路径（锚点/轮询）。不加锁时，两个线程可能同时通过 handles.containsKey 检查，
     * 于是对同一方法挂两次：后一个 handle 覆盖 map 中的前一个，前者失去引用 → **泄漏且卸不掉**。
     * 锁粒度是整个 router 实例；arm 是低频操作（仅注册时刻），无性能顾虑。
     */
    @Synchronized
    private fun armOnClass(req: HookRequest, clazz: Class<*>) {
        val allMethods = clazz.declaredMethods.filter { it.name == req.methodName }
        if (allMethods.isEmpty()) {
            // 方法不是"本类直接声明"（如只写接口方法名）或重载名不存在：提示而非静默
            hostLog("[lsp-hook] MISS method=${req.clsName}#${req.methodName} (declared methods only)")
            return
        }

        // B2：overload 精确选择——按 parameterTypes 描述符串过滤（缺省 sigs=null 时挂全部，现状零改动）
        val methods: List<Method> = if (req.sigs == null) {
            allMethods
        } else {
            val want = req.sigs.map { normalizeSig(it) }
            allMethods.filter { m ->
                m.parameterTypes.map { descriptorOf(it) } == want
            }.also { filtered ->
                if (filtered.isEmpty()) {
                    hostLog(
                        "[lsp-hook] MISS_OVERLOAD ${req.clsName}#${req.methodName} sigs=${req.sigs} " +
                            "(found=${allMethods.size} overloads)"
                    )
                }
            }
        }

        var armed = 0
        var failed = 0
        for (m in methods) {
            // t7（修复 t2 P0#1）：签名入键——每个 overload 独立 key，unhookAll/重注册不再互相覆盖/泄漏
            val key = buildKey(req, methodSig(m))
            if (handles.containsKey(key)) continue
            try {
                val h = hooker.invoke(m).intercept { chain -> intercept(chain, req) }
                handles[key] = h
                armed++
            } catch (t: Throwable) {
                // HookFailedError(Error 族) 也在此兜底记录，不向上抛
                failed++
                hostLog("[lsp-hook] ARM_FAIL ${req.clsName}#${req.methodName} err=${t.message}")
            }
        }
        hostLog(
            "[lsp-hook] ARMED ${req.clsName}#${req.methodName} overloads=$armed mode=${req.mode} " +
                "sigs=${req.sigs ?: "all"} tag=${req.tag}" +
                (if (failed > 0) " failed=$failed" else "")
        )
    }

    /** intercept 主体：observe（现状）与 replace（RouteB）分流。 */
    private fun intercept(chain: XposedInterface.Chain, req: HookRequest): Any? {
        return if (req.mode == "replace") {
            interceptReplace(chain, req)
        } else {
            interceptObserve(chain, req)
        }
    }

    /** observe：命中即日志（现状行为，零改动）。 */
    private fun interceptObserve(chain: XposedInterface.Chain, req: HookRequest): Any? {
        val args = try { chain.getArgs() } catch (_: Throwable) { null }
        hostLog(
            "[lsp-hook] HIT ${req.clsName}#${chain.executable.name} tag=${req.tag} args=${argsSummary(args)}"
        )
        if (req.act == "toast") {
            val ctx = runCatching { chain.getThisObject() }.getOrNull() as? Context ?: appContext
            if (ctx != null) {
                mainHandler.post {
                    runCatching {
                        Toast.makeText(ctx, "[lsp-hook] HIT ${req.clsName}#${req.methodName}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        return chain.proceed()
    }

    /** replace（RouteB B1）：参数编码 → 同步 JS 调用 → 按回复决定 proceed/替换返回。 */
    private fun interceptReplace(chain: XposedInterface.Chain, req: HookRequest): Any? {
        return synchronized(replaceLock) {
            try {
                val payload = buildHookPayload(chain, req.tag)
                val id = "lsp-${reqSeq.incrementAndGet()}"
                var reply = GumJsBridge.callJs(id, payload)

                // 嵌套服务循环：async fn 内 await this.method(...)（同 id 栈复用；B2 支持自定义参数）。
                // innerExecuted/lastInnerResult：原方法已在内层执行过 → 后续超时/观察/CAST/JS_ERR
                // 一律复用其结果（**不再二次 chain.proceed()——否则原方法执行两遍**）。
                var innerExecuted = false
                var lastInnerResult: Any? = null
                var inner = parseInnerArgs(reply)
                while (inner != null) {
                    // B2：非空 args 且按参数类型转换成功 → proceed(convArgs)；否则安全回退原参
                    val method = chain.executable as? Method
                    val conv = if (inner.length() > 0 && method != null) {
                        decodeArgs(method.parameterTypes, inner)
                    } else null
                    val origResult = if (conv != null) chain.proceed(conv) else chain.proceed()
                    lastInnerResult = origResult
                    innerExecuted = true
                    GumJsBridge.postOriginalReply(id, encodeRet(origResult))
                    reply = GumJsBridge.callJs(id, "")
                    inner = parseInnerArgs(reply)
                }

                when {
                    reply.isEmpty() -> {
                        hostLog("[lsp-hook] REPLACE_TIMEOUT tag=${req.tag} id=$id")
                        if (innerExecuted) lastInnerResult else chain.proceed()
                        // 超时兜底：内层未执行 → 原方法必执行；已执行 → 复用其结果
                    }
                    else -> decodeReply(reply, chain, req, innerExecuted, lastInnerResult)
                }
            } catch (t: Throwable) {
                // 注意：不在此处再次 chain.proceed()——若异常来自原方法（proceed 抛出），
                // 再 proceed 会导致原方法二次执行；按框架语义直接上抛（protective 下由框架捕获记录）。
                hostLog("[lsp-hook] REPLACE_ERR tag=${req.tag} err=${t.message}")
                throw t
            }
        }
    }

    private fun decodeReply(
        reply: String,
        chain: XposedInterface.Chain,
        req: HookRequest,
        innerExecuted: Boolean,
        lastInnerResult: Any?,
    ): Any? {
        return try {
            val json = JSONObject(reply)
            // 不用 optString(name, null)：
            //   ① Kotlin 对它报类型不匹配（Java 签名标 @NonNull，传 null 是平台类型灰色地带）；
            //   ② 若 reply 带 JSON null（{"err":null}），opt 返回 JSONObject.NULL，
            //      String.valueOf 会给出字串 "null"，从而被误判为 JS 错误。
            val errRaw = json.opt("err")
            val err = if (errRaw == null || errRaw === JSONObject.NULL) null else errRaw.toString()
            if (err != null) {
                hostLog("[lsp-hook] JS_ERR tag=${req.tag} err=$err")
                // JS 抛错：内层已执行 → 复用原方法结果（不二次执行）；否则 proceed
                return if (innerExecuted) lastInnerResult else chain.proceed()
            }
            if (!json.optBoolean("over")) {
                // 观察语义（返回原值）：内层已执行 → 该值即原方法结果；否则 proceed
                return if (innerExecuted) lastInnerResult else chain.proceed()
            }
            val r = json.opt("r")
            val retType = (chain.executable as? Method)?.returnType ?: Void.TYPE
            if (retType == Void.TYPE) {
                return null   // void：返回被框架忽略
            }
            val v = decodeRet(retType, r)
            if (v === RET_FALLBACK) {
                hostLog("[lsp-hook] CAST_FAIL tag=${req.tag} retType=${retType.simpleName} r=$r")
                return if (innerExecuted) lastInnerResult else chain.proceed()
            }
            v
        } catch (t: Throwable) {
            hostLog("[lsp-hook] DECODE_ERR tag=${req.tag} err=${t.message}")
            if (innerExecuted) lastInnerResult else chain.proceed()
        }
    }

    // ---- 编解码 ----

    private object RET_FALLBACK

    /** argsJson 契约（与 t6 shim 严格一致）：{"key":tag,"args":[...],"this":{...}|null} */
    private fun buildHookPayload(chain: XposedInterface.Chain, tag: String): String {
        val json = JSONObject()
        json.put("key", tag)
        val args = JSONArray()
        val raw = chain.getArgs()
        for (a in raw) args.put(encodeValue(a))
        json.put("args", args)
        val thisObj = chain.getThisObject()
        json.put("this", if (thisObj != null) encodeValue(thisObj) else JSONObject.NULL)
        return json.toString()
    }

    /**
     * 编码（Facts §5）：基础类型直 JSON；对象/数组 → {"__obj":"<simpleName>@<addr>"} 占位
     * （B1 只读不解析；addr = System.identityHashCode 十六进制，进程内稳定）。
     */
    private fun encodeValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is String -> v
        is Char -> v.toString()
        is Boolean -> v
        is Float -> if (v.isNaN() || v.isInfinite()) v.toString() else v
        is Double -> if (v.isNaN() || v.isInfinite()) v.toString() else v
        is Number -> {
            if (v is Long && (v > MAX_SAFE_LONG || v < -MAX_SAFE_LONG)) {
                hostLog("[lsp-hook] LONG_PRECISION warn |v|>2^53 value=$v")
            }
            v
        }
        else -> JSONObject().put(
            "__obj",
            "${v.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(v))}"
        )
    }

    /** r 解码（Facts §5）：Number→按返回类型强转；Boolean→boolean；String→String/null；其它→fallback */
    private fun decodeRet(retType: Class<*>, r: Any?): Any? {
        return when {
            r == null || r === JSONObject.NULL -> null
            // 下面同时比 Java 包装类与 Kotlin 原始类型（如 java.lang.Character vs Char）：
            // 反射拿到的 retType 对 char/int 等方法返回的是原始类型（Character.TYPE），
            // 而包装类场景返回 java.lang.Character —— 两者不等价，必须都查。
            retType == java.lang.String::class.java -> r as? String ?: RET_FALLBACK
            retType == java.lang.Character::class.java || retType == Char::class.java ->
                (r as? String)?.firstOrNull() ?: RET_FALLBACK
            retType == java.lang.Boolean::class.java || retType == Boolean::class.java ->
                r as? Boolean ?: RET_FALLBACK
            retType == java.lang.Integer::class.java || retType == Int::class.java ->
                (r as? Number)?.toInt() ?: RET_FALLBACK
            retType == java.lang.Long::class.java || retType == Long::class.java ->
                (r as? Number)?.toLong() ?: RET_FALLBACK
            retType == java.lang.Short::class.java || retType == Short::class.java ->
                (r as? Number)?.toShort() ?: RET_FALLBACK
            retType == java.lang.Byte::class.java || retType == Byte::class.java ->
                (r as? Number)?.toByte() ?: RET_FALLBACK
            retType == java.lang.Float::class.java || retType == Float::class.java ->
                (r as? Number)?.toFloat() ?: RET_FALLBACK
            retType == java.lang.Double::class.java || retType == Double::class.java ->
                (r as? Number)?.toDouble() ?: RET_FALLBACK
            else -> RET_FALLBACK   // 对象/数组返回：B1 不支持（透传原值）
        }
    }

    /** 原方法结果编码（nativePostOriginalReply 回投载荷）——基础类型透传、对象 → 占位。 */
    private fun encodeRet(v: Any?): String {
        return JSONObject().put("__ret", encodeValue(v)).toString()
    }

    private fun argsSummary(args: List<Any?>?): String {
        if (args == null) return "?"
        return args.joinToString(",") { a ->
            when (a) {
                null -> "null"
                is String -> if (a.length > 48) a.substring(0, 48) + ".." else a
                else -> a.javaClass.simpleName
            }
        }
    }

    // ---- B2：overload sigs 归一化 / 内层参数解码 ----

    /** 解析内层标记：{"__inner":true[, "args":[…]]} → args（无 args 字段/非数组 → null=原参回退） */
    private fun parseInnerArgs(reply: String): JSONArray? {
        return try {
            val json = JSONObject(reply)
            if (!json.optBoolean("__inner")) null else json.optJSONArray("args")
        } catch (_: Throwable) {
            null
        }
    }

    /** 按参数类型把 JSON 数组转换为 Object[]；任何一项不可转换 → null（调用方回退原参） */
    private fun decodeArgs(types: Array<Class<*>>, arr: JSONArray): Array<Any?>? {
        if (arr.length() != types.size) return null
        val out = arrayOfNulls<Any?>(types.size)
        for (i in types.indices) {
            val v = decodeRet(types[i], arr.opt(i))
            if (v === RET_FALLBACK) return null
            out[i] = v
        }
        return out
    }

    /** Java 类型 → JVM 描述符（数组递归） */
    private fun descriptorOf(c: Class<*>): String = when {
        // isArray 为 true 时 componentType 必然非空；Kotlin 无法从 Java 平台类型推断，故显式断言
        c.isArray -> "[" + descriptorOf(c.componentType!!)
        c.isPrimitive -> when (c) {
            java.lang.Integer.TYPE -> "I"
            java.lang.Long.TYPE -> "J"
            java.lang.Float.TYPE -> "F"
            java.lang.Double.TYPE -> "D"
            java.lang.Boolean.TYPE -> "Z"
            java.lang.Byte.TYPE -> "B"
            java.lang.Short.TYPE -> "S"
            java.lang.Character.TYPE -> "C"
            java.lang.Void.TYPE -> "V"
            else -> "L" + c.name.replace('.', '/') + ";"
        }
        else -> "L" + c.name.replace('.', '/') + ";"
    }

    /** JS 侧 sig 归一化（'I'/'int'/'java.lang.String'/'int[]'/'[I' 等形态 → JVM 描述符） */
    private fun normalizeSig(s: String): String {
        val t = s.trim()
        if (t.endsWith("[]")) return "[" + normalizeSig(t.substring(0, t.length - 2))
        if (t.startsWith("[") && t.length > 1) return t   // 已描述符形态
        return when (t) {
            "I", "int" -> "I"
            "J", "long" -> "J"
            "F", "float" -> "F"
            "D", "double" -> "D"
            "Z", "boolean" -> "Z"
            "B", "byte" -> "B"
            "S", "short" -> "S"
            "C", "char" -> "C"
            "V", "void" -> "V"
            else -> if (t.startsWith("L") && t.endsWith(";")) t else "L" + t.replace('.', '/') + ";"
        }
    }

    private companion object {
        const val MAX_SAFE_LONG = 9007199254740992L   // 2^53
    }
}
