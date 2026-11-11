package io.github.shaonialife.carptocar.util

internal object ConnectionIssueSummary {
    private val logTime = Regex("""^\[\d{2}:\d{2}:\d{2}\.\d{3}]\s*""")
    private val failedStage = Regex("""STAGE: ✗ 本轮未连上，卡在「([^」]+)」""")
    private val mac = Regex("""(?i)(?<![\da-f])(?:[\da-f]{2}[:-]){5}[\da-f]{2}(?![\da-f])""")
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val ipv6 = Regex("""(?i)(?<![\da-f:])(?:[\da-f]{0,4}:){2,}[\da-f:]{0,4}(?:%[\w.-]+)?(?![\da-f:])""")
    private val credentials = Regex(
        """(?i)(?:\b(?:psk|password|passphrase|pin|token|sharedSecret)\s*[=:：]|热点密码\s*[:：]?)\s*.*|配对码\s*[:：]?\s*\d+""",
    )

    fun format(appName: String, version: String, target: String, currentStage: String, logs: String): String {
        val recent = logs.lineSequence().filter { LogDisplayLevel.of(it).isIssue }.toList().takeLast(4)
        val lastFailure = failedStage.findAll(logs).lastOrNull()?.groupValues?.get(1)
        return buildString {
            appendLine("$appName $version")
            appendLine("目标车机 · ${sanitize(target).take(60)}")
            appendLine("当前状态 · ${sanitize(currentStage).take(140)}")
            if (lastFailure != null) appendLine("最近失败阶段 · ${sanitize(lastFailure).take(140)}")
            if (recent.isEmpty()) {
                append("暂无异常记录")
            } else {
                appendLine("最近异常")
                append(recent.joinToString("\n") { line ->
                    val time = logTime.find(line)?.value.orEmpty()
                    time + sanitize(line.removePrefix(time)).take(160)
                })
            }
        }
    }

    private fun sanitize(text: String): String {
        val withoutCredentials = credentials.replace(text.lineSequence().first(), "〈已隐藏〉")
        val withoutMac = mac.replace(withoutCredentials, "〈蓝牙地址〉")
        return ipv6.replace(ipv4.replace(withoutMac, "〈IP〉"), "〈IP〉")
    }
}
