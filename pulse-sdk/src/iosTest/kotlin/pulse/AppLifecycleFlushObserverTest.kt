@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package pulse

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import neton.io.net.runReactor
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import pulse.core.EventSink
import pulse.core.JsonEventCodec
import pulse.core.PulseConfig
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLifecycleFlushObserverTest {
    @Test
    fun lifecycleNotificationsAreConsumedOnlyOnTheReactor() = runReactor {
        val pulse = Pulse.start(this, PulseConfig(
            projectId = "lifecycle-thread", host = "127.0.0.1", port = 1,
            analytics = false, apm = false, runtime = false,
            storageDir = "/tmp/pulse-lifecycle-${kotlin.random.Random.nextLong()}",
        ))
        val owner = Worker.current.id
        val names = mutableListOf<String>()
        pulse.client.attachSink(object : EventSink {
            override suspend fun send(batch: ByteArray) {
                assertEquals(owner, Worker.current.id)
                names += JsonEventCodec.decode(batch).events.map { it.name }
            }
            override suspend fun close() {}
        })
        val auto = AutoInstrumentation(pulse, this)
        auto.install()
        val producer = Worker.start(name = "lifecycle-producer")
        try {
            producer.execute(TransferMode.SAFE, { Unit }) {
                repeat(10) {
                    NSNotificationCenter.defaultCenter.postNotificationName(
                        UIApplicationDidBecomeActiveNotification, `object` = null,
                    )
                    NSNotificationCenter.defaultCenter.postNotificationName(
                        UIApplicationDidEnterBackgroundNotification, `object` = null,
                    )
                }
            }.result
            // reactor 尚未让出执行权：其他线程的通知不能直接向 EventBuffer 写入。
            pulse.client.flushOnce()
            assertEquals(emptyList(), names)
            withTimeout(2_000) {
                while (names.count { it == "app_background" } < 10) delay(10)
            }
            assertEquals(10, names.count { it == "app_foreground" })
            auto.close()
            val count = names.size
            NSNotificationCenter.defaultCenter.postNotificationName(
                UIApplicationDidBecomeActiveNotification, `object` = null,
            )
            delay(10)
            pulse.client.flushOnce()
            assertEquals(count, names.size)
        } finally {
            auto.close()
            pulse.stop()
            producer.requestTermination().result
        }
    }

    @Test
    fun invokesFlushOnBackgroundAndStopsAfterClose() {
        var calls = 0
        val observer = AppLifecycleFlushObserver { calls++ }

        NSNotificationCenter.defaultCenter.postNotificationName(
            UIApplicationDidEnterBackgroundNotification,
            `object` = null,
        )
        assertEquals(1, calls)

        observer.close()
        NSNotificationCenter.defaultCenter.postNotificationName(
            UIApplicationDidEnterBackgroundNotification,
            `object` = null,
        )
        assertEquals(1, calls)
    }
}
