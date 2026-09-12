package com.redcut.core.media

import com.google.common.truth.Truth.assertThat
import com.redcut.core.common.logging.NoOpRedcutLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The broker's concurrency rules (spec §9.1, risk R4).
 *
 * This is the class whose whole purpose is to stand between the app and a codec pool that
 * throws when it is exceeded, so the tests are about what it does under pressure rather than
 * about its happy path: how many decoders run at once, whether a failure gives its slot back,
 * and what the capacity clamp actually clamps to.
 *
 * It runs in CI's `build` job (`:core:media:testDebugUnitTest`) — an Android module has no test
 * runner on the development host, which is the constraint the whole repo's local loop is shaped
 * around. The capacity arrives as a constructor parameter precisely so these tests do not need a
 * device to exist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaResourceBrokerTest {

    private fun broker(capacity: Int) = MediaResourceBroker(
        codecCapacity = capacity,
        logger = NoOpRedcutLogger,
    )

    @Test
    fun `never runs more decoders at once than the capacity`() = runTest {
        val broker = broker(capacity = 2)
        val concurrent = AtomicInteger(0)
        val peak = AtomicInteger(0)

        val work = (1..8).map {
            async(Dispatchers.Default) {
                broker.withDecoder {
                    val now = concurrent.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, now) }
                    delay(5)
                    concurrent.decrementAndGet()
                }
            }
        }
        work.awaitAll()

        assertThat(peak.get()).isAtMost(2)
        assertThat(concurrent.get()).isEqualTo(0)
        // All eight ran: the broker queues, it does not drop.
        assertThat(broker.availableDecoders).isEqualTo(2)
    }

    @Test
    fun `a failing decode gives its slot back`() = runTest {
        // A codec exception on one thumbnail must not cost the app a decoder for the rest of the
        // session — a leaked slot is precisely the slow starvation §9.1 exists to prevent.
        val broker = broker(capacity = 1)

        runCatching { broker.withDecoder { error("codec blew up") } }

        assertThat(broker.availableDecoders).isEqualTo(1)
        // And the next caller gets in rather than hanging forever.
        val result = broker.withDecoder { "ok" }
        assertThat(result).isEqualTo("ok")
    }

    @Test
    fun `a cancelled decode gives its slot back`() = runTest {
        val broker = broker(capacity = 1)
        val job = async(Dispatchers.Default) { broker.withDecoder { delay(10_000) } }

        delay(10)
        job.cancel()
        job.join()

        assertThat(broker.availableDecoders).isEqualTo(1)
    }

    @Test
    fun `the encoder slot is exclusive`() = runTest {
        val broker = broker(capacity = 3)
        val concurrent = AtomicInteger(0)
        val peak = AtomicInteger(0)

        val work = (1..4).map {
            async(Dispatchers.Default) {
                broker.withEncoder {
                    val now = concurrent.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, now) }
                    delay(5)
                    concurrent.decrementAndGet()
                }
            }
        }
        work.awaitAll()

        // Export is serialised by definition (spec §8.3), whatever the decoder capacity is.
        assertThat(peak.get()).isEqualTo(1)
    }

    @Test
    fun `capacity is clamped into the spec's range`() {
        // Never zero (a pool of zero slots is a deadlock dressed as a limit) and never unbounded
        // (a device that claims a dozen concurrent decoders OOMs instead of throwing).
        assertThat(broker(capacity = 0).decoderCapacity).isEqualTo(1)
        assertThat(broker(capacity = -5).decoderCapacity).isEqualTo(1)
        assertThat(broker(capacity = 2).decoderCapacity).isEqualTo(2)
        assertThat(broker(capacity = 99).decoderCapacity).isEqualTo(3)
    }

    @Test
    fun `decoder and encoder slots do not block each other`() = runTest {
        // The clamps are per pool. An export holding the encoder must not stop a thumbnail
        // decode, or the timeline would go grey during every export.
        val broker = broker(capacity = 1)

        val encoder = async(Dispatchers.Default) { broker.withEncoder { delay(20) } }
        delay(5)
        val thumb = async(Dispatchers.Default) { broker.withDecoder { "decoded" } }

        assertThat(thumb.await()).isEqualTo("decoded")
        encoder.await()
    }
}
