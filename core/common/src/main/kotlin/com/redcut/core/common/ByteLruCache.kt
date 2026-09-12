package com.redcut.core.common

/**
 * The JDK's own default load factor, named rather than written inline: 0.75 here is not a
 * choice this class is making, and a bare literal in a constructor call reads as if it were.
 */
private const val LOAD_FACTOR = 0.75f

/**
 * A least-recently-used cache bounded by the SIZE of what it holds, not by a count.
 *
 * ### Why bytes and not entries
 *
 * Because the entries are images of wildly different sizes (spec §9.3: thumbnails decoded at
 * ≤ 160 px wide, cached in an LRU "sized in *bytes* (16 MB)"). A cache limited to "64 entries"
 * holds 0.5 MB of tiny frames or 64 MB of large ones — the bound does not bound anything the
 * device actually runs out of. The caller supplies [sizeOf] for exactly this reason: only the
 * caller knows how to measure its own values, and keeping that knowledge out of here is what
 * lets this class be tested on a JVM with `Int`-sized payloads instead of bitmaps.
 *
 * ### Why it is generic, and why it is in `:core:common`
 *
 * It is a data structure, not media: nothing here knows what a thumbnail is. It lives in the
 * pure tier because the eviction arithmetic is the part with rules in it, and spec §12.1 puts
 * rules where they can be tested in milliseconds — the device-side code that fills the cache
 * is a thin wrapper this class cannot make wrong.
 *
 * ### Not thread-safe on purpose
 *
 * The callers that matter (thumbnail extraction behind `MediaResourceBroker`, spec §9.1) are
 * already serialised by the broker's semaphore. Adding a lock here would cost a synchronised
 * section per frame for a guarantee the caller has to provide anyway — and a false sense of
 * safety for any future caller that does not.
 */
class ByteLruCache<K : Any, V : Any>(
    /** The ceiling, in bytes. Entries are evicted from the least recently used end. */
    private val maxBytes: Int,
    /** How many bytes [value] occupies. Never 0 for a value worth caching. */
    private val sizeOf: (V) -> Int,
) {
    // `accessOrder = true`: iteration follows ACCESS order, which is what makes the least
    // recently used entry the first one [trimToFit] sees and evicts. With the default
    // (insertion order) this class would be a FIFO with an LRU's name.
    private val entries = LinkedHashMap<K, V>(0, LOAD_FACTOR, true)

    var bytes: Int = 0
        private set

    val size: Int get() = entries.size

    init {
        require(maxBytes > 0) { "maxBytes must be > 0, was $maxBytes" }
    }

    /** The value for [key], marking it as most recently used. Null when absent. */
    operator fun get(key: K): V? = entries[key]

    fun contains(key: K): Boolean = entries.containsKey(key)

    /**
     * Stores [value], evicting least-recently-used entries until it fits.
     *
     * An entry larger than the whole cache is stored and immediately evicted (it cannot fit),
     * which is deliberate: the alternative — rejecting it — would make the cache silently
     * useless for the one large image a caller happens to ask for, and the caller would have
     * no way to tell that from a miss. Returning after storing means the value was handed back
     * to the caller either way; the cache is an optimisation, never a source of truth.
     */
    fun put(key: K, value: V) {
        entries.remove(key)?.let { bytes -= sizeOf(it) }

        val added = sizeOf(value)
        entries[key] = value
        bytes += added

        trimToFit()
    }

    /** Drops everything (spec §9.3: `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)`). */
    fun clear() {
        entries.clear()
        bytes = 0
    }

    /**
     * Evicts from the least recently used end until [bytes] is within [maxBytes].
     *
     * The map's access order is what makes this correct: `entries.keys.first()` is the entry
     * that has gone longest without being read, so a cache that is being read from stays warm
     * and a cache that is only being written to does not.
     */
    private fun trimToFit() {
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val (_, victim) = iterator.next()
            bytes -= sizeOf(victim)
            iterator.remove()
        }
    }
}
