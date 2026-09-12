package com.redcut.core.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.redcut.core.common.logging.RedcutLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single owner of the device's scarce decoder and encoder instances (spec §9.1, risk R4).
 *
 * Android exposes a small, globally shared pool of hardware codecs, and exceeding it does not
 * degrade — it throws `MediaCodec.CodecException`. The spec's rule is absolute: "Nothing in the
 * app may construct a `MediaCodec` (or a Media3 `Transformer`/`CompositionPlayer`) except
 * through the broker."
 *
 * What that buys, in the spec's own words: preview and export cannot run simultaneously (which
 * is correct — running both exhausts codecs and thrashes memory), thumbnail extraction queues
 * behind playback, and the failure mode becomes a short wait instead of a crash.
 *
 * ### Why the capacity arrives as a parameter
 *
 * The default comes from the platform ([detectHardwareCodecCapacity]), but the class itself
 * never calls it during construction. That keeps the concurrency rules — the part with
 * behaviour in it — testable without a device: a test constructs the broker with a capacity of
 * 2 and asserts that a third caller waits.
 *
 * `coerceIn(1, 3)` is the spec's clamp, and both ends of it are deliberate. The lower bound
 * because zero permits would deadlock the first caller forever; the upper because a device
 * that claims to expose a dozen concurrent 1080p decoders is a device that will OOM instead of
 * throwing, which is a worse failure than a wait.
 */
@Singleton
class MediaResourceBroker @Inject constructor(
    /** How many hardware decoders may be in use at once. Defaults to the platform's answer. */
    codecCapacity: Int = detectHardwareCodecCapacity(),
    /** Where a clamp or a wait is worth recording. */
    private val logger: RedcutLogger,
) {

    /** The clamped capacity. Exposed so a caller (or a test) can state what it was given. */
    val decoderCapacity: Int = codecCapacity.coerceIn(MIN_DECODERS, MAX_DECODERS)

    private val decoderSlots = Semaphore(decoderCapacity)
    private val encoderSlots = Semaphore(ENCODER_SLOTS)

    /**
     * Runs [block] holding one decoder slot, releasing it whatever happens.
     *
     * Suspends rather than fails when the pool is full: the caller is a thumbnail decode or a
     * preview, both of which can wait. `withPermit` is what makes the release unconditional —
     * a cancellation (the user left the screen mid-decode) or a codec exception must not leak
     * a slot, because a leaked slot is a decoder the app never gets back.
     */
    suspend fun <T> withDecoder(block: suspend () -> T): T = decoderSlots.withPermit { block() }

    /** Runs [block] holding the single encoder slot. Export is the only intended caller. */
    suspend fun <T> withEncoder(block: suspend () -> T): T = encoderSlots.withPermit { block() }

    /** Free decoder slots right now. A diagnostic, not a synchronisation primitive. */
    val availableDecoders: Int get() = decoderSlots.availablePermits

    /**
     * Where a caller reports that a slot was unavailable for a noticeable time.
     *
     * Separated from [withDecoder] on purpose: the broker does not measure time (it has no
     * clock), and a wait that nobody records is invisible until someone complains that scrubbing
     * stutters (spec §9.1's "short wait" is the intended behaviour, and this is how it is
     * confirmed to still be short).
     */
    fun reportWait(what: String, waitedMs: Long) {
        logger.d(TAG, "$what waited ${waitedMs}ms for a codec slot (capacity $decoderCapacity)")
    }

    private companion object {
        const val TAG = "MediaResourceBroker"

        /** Never zero: a pool of zero slots is a deadlock dressed as a limit. */
        const val MIN_DECODERS = 1

        /** Never more than three: beyond this a device OOMs instead of queueing (spec §9.1). */
        const val MAX_DECODERS = 3

        /** Export is serialised by definition (spec §8.3): one encoder, one export. */
        const val ENCODER_SLOTS = 1
    }
}

/**
 * How many video decoders this device can run at once.
 *
 * Counts the hardware-accelerated video decoder codecs the platform advertises for AVC, which
 * is the codec every Android device is required to decode. The count is an approximation of a
 * number the platform does not publish — the honest framing is that it is a starting point the
 * clamp then bounds, not a measured pool size.
 *
 * Never returns a value the caller must validate: [MediaResourceBroker]'s constructor is where
 * the clamping belongs, so this can answer 0 on a device with no decoders at all without the
 * app having to think about it.
 */
internal fun detectHardwareCodecCapacity(): Int {
    val decoders = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.count { info ->
            !info.isEncoder && info.isHardwareAccelerated && info.supportsVideoMime(VIDEO_AVC)
        }
    }.getOrDefault(0)

    // A device that reports none is a device with an incomplete codec list (some emulators),
    // not a device that cannot decode video: one slot keeps the app working and serialised.
    return decoders.coerceAtLeast(1)
}

private const val VIDEO_AVC = "video/avc"

private fun MediaCodecInfo.supportsVideoMime(mime: String): Boolean =
    supportedTypes.any { it.equals(mime, ignoreCase = true) }

/**
 * The broker, with the platform's capacity.
 *
 * The Android answer arrives here rather than inside the class so that the class stays free of
 * static platform calls — the same split `:engine:native` uses for its contract, and the reason
 * a test can hand it a capacity of 2.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object MediaResourceBrokerModule {

    @Provides
    @Singleton
    fun provideMediaResourceBroker(logger: RedcutLogger): MediaResourceBroker =
        MediaResourceBroker(codecCapacity = detectHardwareCodecCapacity(), logger = logger)
}
