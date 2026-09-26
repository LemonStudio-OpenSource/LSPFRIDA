package com.bail.lspfrifa.xposed

import kotlin.collections.ArrayDeque

/**
 * D14：早期日志缓冲（目标进程内）。
 *
 * onPackageLoaded / onPackageReady / init 链早期阶段，宿主侧 ILogReceiver 尚未建立
 * （register_ipc 甚至可能延迟重试），此期间若直接丢弃日志，注入过程将无任何可查证据 ——
 * 而这正是「注入失败但不知道为什么」的高发区间。
 *
 * 做法：内存保留最近 CAPACITY 行；flush（握手成功）时一次性补发并切换到实时模式
 * （此后 add 变为空操作，避免与实时通道重复）。
 *
 * 单进程内单例；全部方法 synchronized。
 */
internal object EarlyLogBuffer {

    /** 上限 500 行：注入链正常不超过数十行，500 足够覆盖重试场景且内存可忽略。 */
    private const val CAPACITY = 500

    private val lines = ArrayDeque<String>(CAPACITY)

    @Volatile
    private var flushed = false

    /** 记录一行（已 flush 后为空操作 —— 实时通道已接管）。 */
    @Synchronized
    fun add(line: String) {
        if (flushed) return
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast(line)
    }

    /**
     * 一次性补发全部缓存并切换到实时模式。
     * 幂等：重复调用直接返回（防多次握手重复补发）。
     */
    @Synchronized
    fun flush(sink: (String) -> Unit) {
        if (flushed) return
        flushed = true
        val snapshot = lines.toList()
        lines.clear()
        snapshot.forEach { line -> runCatching { sink(line) } }
    }

    /** 当前待补发行数（诊断用）。 */
    @Synchronized
    fun pendingCount(): Int = lines.size
}
