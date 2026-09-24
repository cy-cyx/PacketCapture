package com.packetcapture.core

/** 通过同步锁保证通知栏停止与页面启动不会交错；非法转换立即失败，避免留下半启动的 VPN。 */
class CaptureLifecycle {
    var phase: CapturePhase = CapturePhase.IDLE
        private set
    @Synchronized fun transition(next: CapturePhase) {
        if (next == phase) return
        val allowed = when (phase) {
            CapturePhase.IDLE -> setOf(CapturePhase.STARTING)
            CapturePhase.STARTING -> setOf(CapturePhase.CAPTURING, CapturePhase.STOPPING, CapturePhase.FAILED)
            CapturePhase.CAPTURING -> setOf(CapturePhase.STOPPING, CapturePhase.FAILED)
            CapturePhase.STOPPING -> setOf(CapturePhase.IDLE, CapturePhase.FAILED)
            CapturePhase.FAILED -> setOf(CapturePhase.STARTING, CapturePhase.STOPPING, CapturePhase.IDLE)
        }
        require(next in allowed) { "非法抓包状态转换: $phase → $next" }
        phase = next
    }
}
fun List<Header>.firstHeader(name: String): String? = firstOrNull { it.name.equals(name, true) }?.value
fun matchesBypass(host: String, rules: Set<String>): Boolean {
    val normalized = host.lowercase().trimEnd('.')
    return rules.any { raw ->
        val rule = raw.trim().lowercase().trimEnd('.')
        if (rule.startsWith("*.")) normalized.endsWith(rule.drop(1)) && normalized != rule.drop(2)
        else normalized == rule
    }
}
