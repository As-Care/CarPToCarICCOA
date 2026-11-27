package io.github.shaonialife.carptocar.video

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.carptocar.MainActivity
import io.github.shaonialife.carptocar.R
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.airplay.AirPlayClient
import io.github.shaonialife.carptocar.airplay.StreamFrameCipher
import io.github.shaonialife.carptocar.carplus.CarPlusReceiver
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import io.github.shaonialife.carptocar.launcher.settings.AutoConnectPrefs
import io.github.shaonialife.carptocar.legal.DisclaimerPrefs
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.FutureTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the CarPlusMac emulator with the runtime APK and -e headUnitLoopback true. */
@RunWith(AndroidJUnit4::class)
class HeadUnitReconnectTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun reconnectKeepsReceiverAndRestartsCarOnce() = withConnectedCar { activity, control ->
        clickReconnectTwice(activity)
        acknowledgeTeardown(control)
        await { (SessionController.reconnectWaitSeconds() ?: 0) in 1..5 }
        assertTrue(CarPlusReceiver.isRunning)
        // Read after the main-thread callback has both cleared the timer and started Bluetooth.
        await { onMain { SessionController.reconnectWaitSeconds() == null } }
        assertTrue(CarPlusReceiver.isRunning)
        assertTrue(SessionController.isSessionRunning())
        assertEquals(1, SessionController.logText().lineSequence().count { "BT-C: 启动" in it })
    }

    @Test fun fullDisconnectDuringTeardownWins() = withConnectedCar { activity, control ->
        onMain {
            activity.findViewById<View>(R.id.btnReconnectCar).performClick()
            assertTrue(SessionController.disconnecting)
            cancelFromCard(activity)
        }
        acknowledgeTeardown(control)
        verifyStopped()
    }

    @Test fun cancelDuringCooldownWins() = withConnectedCar { activity, control ->
        clickReconnectTwice(activity)
        await { (SessionController.reconnectWaitSeconds() ?: 0) > 0 }
        await { onMain { activity.findViewById<TextView>(R.id.ctaLabel).text == "取消连接" } }
        // No RTSP reply: exercise the timeout before cancelling the queued Bluetooth start.
        assertEquals("TEARDOWN / RTSP/1.0", control.getInputStream().bufferedReader().readLine())
        onMain { cancelFromCard(activity) }
        verifyStopped()
    }

    private fun clickReconnectTwice(activity: MainActivity) = onMain {
        val button = activity.findViewById<View>(R.id.btnReconnectCar)
        button.performClick()
        button.performClick()
        assertTrue(SessionController.disconnecting)
    }

    private fun cancelFromCard(activity: MainActivity) {
        assertEquals("取消连接", activity.findViewById<TextView>(R.id.ctaLabel).text.toString())
        activity.findViewById<View>(R.id.ctaButton).performClick()
        val actions = WindowInspector.getGlobalWindowViews().mapNotNull {
            it.findViewById<LinearLayout>(R.id.msgActions)
        }.single()
        (0 until actions.childCount).map { actions.getChildAt(it) as TextView }
            .single { it.text == "取消连接" }.performClick()
    }

    private fun acknowledgeTeardown(control: Socket) {
        val input = control.getInputStream().bufferedReader(Charsets.US_ASCII)
        assertEquals("TEARDOWN / RTSP/1.0", input.readLine())
        val headers = generateSequence(input::readLine).takeWhile { it.isNotEmpty() }.toList()
        val cseq = headers.single { it.startsWith("CSeq:") }
        control.getOutputStream().write("RTSP/1.0 200 OK\r\n$cseq\r\nContent-Length: 0\r\n\r\n".toByteArray())
    }

    private fun verifyStopped() {
        await { !SessionController.disconnecting && !CarPlusReceiver.isRunning }
        SystemClock.sleep(5_500)
        assertNull(SessionController.reconnectWaitSeconds())
        assertFalse(SessionController.isSessionRunning())
        assertFalse(CarPlusReceiver.isRunning)
        assertFalse(SessionController.logText().contains("BT-C: 启动"))
    }

    private fun withConnectedCar(test: (MainActivity, Socket) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("headUnitLoopback") == "true")
        val autoConnect = AutoConnectPrefs.enabled(context)
        val accepted = DisclaimerPrefs.hasAcceptedCurrent(context)
        AutoConnectPrefs.setEnabled(context, false)
        DisclaimerPrefs.accept(context)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            onMain {
                CarPlusReceiver.start(context)
                SessionController.startConnection(context)
            }
            await { onMain { SessionController.reconnectWaitSeconds() == null && CarPlusReceiver.isRunning } }
            val local = InetAddress.getByName("127.0.0.1")
            ServerSocket(0, 1, local).use { sink ->
                val sender = VideoSender("127.0.0.1", sink.localPort, StreamFrameCipher.ChaCha(ByteArray(32)), 640, 360, log = {})
                assertTrue(sender.startPassthrough())
                sink.accept().use {
                    ServerSocket(0, 1, local).use { listener ->
                        val endpoint = Iap2SenderSession.CarPlayEndpoint(
                            "test", "", 0, 0, listener.localPort, null, null, null, listOf("127.0.0.1"),
                        )
                        val client = AirPlayClient(endpoint, log = {})
                        assertTrue(client.reconnect())
                        listener.accept().use { control ->
                            control.soTimeout = 3_000
                            onMain {
                                SessionController.currentClient = client
                                SessionController.videoSender = sender
                            }
                            await { onMain { activity.findViewById<View>(R.id.btnReconnectCar).isShown } }
                            SessionController.clearLog()
                            test(activity, control)
                        }
                    }
                }
            }
        } finally {
            onMain {
                SessionController.disconnect(context, graceful = false)
                activity.finish()
            }
            AutoConnectPrefs.setEnabled(context, autoConnect)
            if (!accepted) DisclaimerPrefs.reset(context)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            assertTrue("Timed out waiting for connection state: " + SessionController.logText().takeLast(700),
                SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(50)
        }
    }

    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(action)
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
