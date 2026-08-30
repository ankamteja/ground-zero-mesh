package org.groundzero.mesh.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The outbox is written by transport callback threads and swept by the service's maintenance
 * ticker, so its two mutating paths genuinely run at once on a phone.
 *
 * The bug this pins: `sweep()` used to expire frames and then remove empty buckets in two
 * separate passes, while `offer()` fetched its bucket and then added to it. An offer landing
 * between the sweep's two passes added its frame to a list the sweep had just dropped from
 * the map — the report was accepted, buffered nowhere, and silently never replayed. It needs
 * concurrency to reproduce, which is why it survived the single-threaded tests.
 */
class StoreAndForwardConcurrencyTest {

    @Test
    fun aFrameOfferedDuringASweepIsNeverLost() {
        repeat(20) { attempt ->
            val buffer = StoreAndForward(ttlMs = 60_000)
            val offered = AtomicInteger()
            val start = CountDownLatch(1)

            val writers = (0 until 4).map { w ->
                Thread {
                    start.await()
                    repeat(100) { i ->
                        buffer.offer("zone-$w", "victim-$w-$i", byteArrayOf(w.toByte(), i.toByte()))
                        offered.incrementAndGet()
                    }
                }
            }
            // 100 per zone stays under maxPerBucket, so every offer must survive: anything
            // missing at the end was lost to the race, not to eviction.
            val sweeper = Thread {
                start.await()
                repeat(2_000) { buffer.sweep() }
            }

            (writers + sweeper).forEach { it.isDaemon = true; it.start() }
            start.countDown()
            (writers + sweeper).forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }

            assertEquals(400, offered.get())
            assertEquals("attempt $attempt lost buffered frames", 400, buffer.size())
            assertEquals(400, buffer.drainAll().size)
        }
    }

    @Test
    fun sweepStillDropsExpiredFramesAndEmptyBuckets() {
        var now = 0L
        val buffer = StoreAndForward(ttlMs = 100, clock = { now })
        buffer.offer("zone-a", "k1", byteArrayOf(1))
        buffer.offer("zone-b", "k2", byteArrayOf(2))
        assertEquals(2, buffer.size())

        now = 101
        buffer.sweep()
        assertEquals(0, buffer.size())
        assertTrue(buffer.drainAll().isEmpty())

        // The bucket having been removed must not stop a later offer for the same zone.
        buffer.offer("zone-a", "k3", byteArrayOf(3))
        assertEquals(1, buffer.size())
    }
}
