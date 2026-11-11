package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LogDisplayLevelTest {
    @Test
    fun recognizesExplicitErrorsAndExistingFailureMessages() {
        assertEquals(LogDisplayLevel.ERROR, LogDisplayLevel.of("[09:22:35.113] [ERROR] SETUP"))
        assertEquals(LogDisplayLevel.ERROR, LogDisplayLevel.of("[09:22:35.113] SESSION 失败: iAP2 链路协商超时"))
        assertEquals(LogDisplayLevel.ERROR, LogDisplayLevel.of("AIRPLAY: 音频流协商失败 - Broken pipe"))
        assertEquals(LogDisplayLevel.WARNING, LogDisplayLevel.of("PROTO! WARN [UNSOLICITED_MESSAGE] 发了未声明的消息"))
    }

    @Test
    fun keepsHandshakeEventsAndRawPacketsOutOfIssues() {
        assertEquals(LogDisplayLevel.PROTOCOL, LogDisplayLevel.of("[ICCOA / CarPlay] 握手"))
        assertEquals(LogDisplayLevel.PROTOCOL, LogDisplayLevel.of("[09:22:38.175] iap2 link ready peerMaxControl=2038"))
        assertEquals(LogDisplayLevel.PROTOCOL, LogDisplayLevel.of("AIRPLAY: ✓ 主屏流协商成功"))
        assertEquals(LogDisplayLevel.NORMAL, LogDisplayLevel.of("RAW rx: 40 40"))
        assertEquals(LogDisplayLevel.NORMAL, LogDisplayLevel.of("WIFI: 失败则继续等待"))
    }
}
