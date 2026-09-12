package com.redcut.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The byte-bounded LRU (spec §9.3).
 *
 * Values here are `Int`s read as "bytes" so the arithmetic is checkable by hand — the eviction
 * rules are the same ones the thumbnail cache relies on, and they are tested without a device,
 * a bitmap, or a decoder.
 */
class ByteLruCacheTest {

    private fun cache(
        maxBytes: Int,
        sizeOf: (Int) -> Int = {
            it
        },
    ) = ByteLruCache<Int, Int>(maxBytes, sizeOf)

    @Test
    fun `stores and returns a value`() {
        val cache = cache(maxBytes = 100)

        cache.put(1, 40)

        assertThat(cache[1]).isEqualTo(40)
        assertThat(cache.bytes).isEqualTo(40)
        assertThat(cache.size).isEqualTo(1)
    }

    @Test
    fun `a miss is null, not a crash`() {
        assertThat(cache(maxBytes = 100)[7]).isNull()
    }

    @Test
    fun `evicts the least recently used entry when the budget is exceeded`() {
        val cache = cache(maxBytes = 100)
        cache.put(1, 40)
        cache.put(2, 40)

        cache.put(3, 40) // total would be 120

        assertThat(cache[1]).isNull()
        assertThat(cache[2]).isEqualTo(40)
        assertThat(cache[3]).isEqualTo(40)
        assertThat(cache.bytes).isEqualTo(80)
    }

    @Test
    fun `reading an entry makes it survive the next eviction`() {
        // The whole point of an LRU over a FIFO: the frames the user is looking at stay cached
        // even while new ones are decoded behind them.
        val cache = cache(maxBytes = 100)
        cache.put(1, 40)
        cache.put(2, 40)

        cache[1] // touch 1, so 2 becomes the least recently used

        cache.put(3, 40)

        assertThat(cache[1]).isEqualTo(40)
        assertThat(cache[2]).isNull()
    }

    @Test
    fun `replacing a key does not double-count its bytes`() {
        val cache = cache(maxBytes = 100)
        cache.put(1, 40)

        cache.put(1, 60)

        assertThat(cache.bytes).isEqualTo(60)
        assertThat(cache.size).isEqualTo(1)
    }

    @Test
    fun `an oversized entry is stored and immediately evicted, leaving the cache usable`() {
        // A single 200-byte image in a 100-byte cache: the cache cannot hold it, and the
        // alternative (refusing it) would look identical to a miss at the call site.
        val cache = cache(maxBytes = 100)

        cache.put(1, 200)

        assertThat(cache.size).isEqualTo(0)
        assertThat(cache.bytes).isEqualTo(0)

        cache.put(2, 30)
        assertThat(cache[2]).isEqualTo(30)
    }

    @Test
    fun `several entries are evicted when one insertion needs the room`() {
        val cache = cache(maxBytes = 100)
        cache.put(1, 30)
        cache.put(2, 30)
        cache.put(3, 30)

        cache.put(4, 70) // 90 + 70 = 160, so two 30s must go

        assertThat(cache.size).isEqualTo(2)
        assertThat(cache[1]).isNull()
        assertThat(cache[2]).isNull()
        assertThat(cache[3]).isEqualTo(30)
        assertThat(cache[4]).isEqualTo(70)
        assertThat(cache.bytes).isEqualTo(100)
    }

    @Test
    fun `the caller's size function is what bounds the cache, not the value's identity`() {
        // Sizes that vary per value: this is the thumbnail case, where one frame is 4 KB and
        // the next is 40 KB.
        val cache = cache(maxBytes = 100, sizeOf = { it })
        cache.put(1, 10)
        cache.put(2, 80)

        cache.put(
            3,
            20,
        ) // evicts 1 (10) and then 2 (80)? no: LRU order is 1, 2 -> evict 1, then fits

        assertThat(cache[1]).isNull()
        assertThat(cache[2]).isEqualTo(80)
        assertThat(cache[3]).isEqualTo(20)
        assertThat(cache.bytes).isEqualTo(100)
    }

    @Test
    fun `clear drops everything and resets the byte count`() {
        val cache = cache(maxBytes = 100)
        cache.put(1, 40)
        cache.put(2, 40)

        cache.clear()

        assertThat(cache.size).isEqualTo(0)
        assertThat(cache.bytes).isEqualTo(0)
        assertThat(cache[1]).isNull()
    }

    @Test
    fun `a zero capacity cache is rejected at construction`() {
        // A zero-byte cache is a bug in the caller: the intent was always "some budget", and
        // failing here names the culprit instead of silently caching nothing forever.
        val failure = runCatching { cache(maxBytes = 0) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `keys can be structured, which is what the thumbnail cache needs`() {
        // (sourceId, positionUs) — the key spec §9.3 names. A data class key is what makes
        // "the same frame asked for twice" a hit rather than a second decode.
        val cache = ByteLruCache<ThumbKey, String>(maxBytes = 100) { it.length }
        cache.put(ThumbKey("src-1", 0L), "a".repeat(60))

        assertThat(cache[ThumbKey("src-1", 0L)]).isNotNull()
        assertThat(cache[ThumbKey("src-1", 1L)]).isNull()
    }

    private data class ThumbKey(val sourceId: String, val positionUs: Long)
}
