package io.github.shaonialife.carptocar.util

/** Classifies existing plain-text logs for display. The original text is used for export. */
internal enum class LogDisplayLevel(val isIssue: Boolean) {
    NORMAL(false),
    PROTOCOL(false),
    WARNING(true),
    ERROR(true),
    ;

    companion object {
        private val timestamp = Regex("""^\[\d{2}:\d{2}:\d{2}\.\d{3}]\s*""")
        private val errorLabel = Regex("""^(?:\[(?:ERROR|FATAL)]|PROTO! ERROR\b)""", RegexOption.IGNORE_CASE)
        private val warningLabel = Regex("""^(?:\[WARN(?:ING)?]|PROTO! WARN\b)""", RegexOption.IGNORE_CASE)
        private val failure = Regex(
            """(?:失败|超时|出错|异常|中止)(?=$|[\s：:（(，,。！!—-])|\b(?:ECONNREFUSED|ETIMEDOUT|Connection reset|Broken pipe)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val protocolLabel = Regex("""^\[(?:ICCOA|CarPlay|AirPlay)(?:\s*/\s*(?:ICCOA|CarPlay|AirPlay))?]""", RegexOption.IGNORE_CASE)
        private val protocolEvent = Regex(
            """^(?:ICCOA:.*(?:广播|配对|会话状态|已连接)|AIRPLAY:.*(?:配对|SETUP|RECORD|协商)|iap2 (?:link ready|tx=0x(?:1d02|4e0e)|rx=0x(?:5703|4301))|IAPLINK:.*链路状态)""",
            RegexOption.IGNORE_CASE,
        )

        fun of(line: String): LogDisplayLevel {
            val message = timestamp.replaceFirst(line, "")
            return when {
                message.startsWith("RAW ") -> NORMAL
                errorLabel.containsMatchIn(message) -> ERROR
                warningLabel.containsMatchIn(message) -> WARNING
                failure.containsMatchIn(message) -> ERROR
                protocolLabel.containsMatchIn(message) || protocolEvent.containsMatchIn(message) -> PROTOCOL
                else -> NORMAL
            }
        }
    }
}
