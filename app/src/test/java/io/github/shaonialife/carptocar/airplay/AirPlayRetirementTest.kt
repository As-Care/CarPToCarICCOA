package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayRetirementTest {
    @Test
    fun retirementClosesControlAndPreventsAnotherConnection() {
        ServerSocket(0).use { server ->
            val client = AirPlayClient(
                Iap2SenderSession.CarPlayEndpoint(
                    ssid = "test", passphrase = "", channel = 0, securityType = 0,
                    ipAddresses = listOf("127.0.0.1"), airPlayPort = server.localPort,
                    deviceIdentifier = null, publicKey = null, sourceVersion = null,
                ),
                log = {},
            )
            assertTrue(client.reconnect(1_000))
            server.accept().use { peer ->
                peer.soTimeout = 1_000
                assertTrue(client.retire())
                assertEquals(-1, peer.getInputStream().read())
                assertFalse(client.retire())
                assertFalse(client.reconnect(1_000))
                client.postControlIap(byteArrayOf(0x55))
            }
        }
    }
}
