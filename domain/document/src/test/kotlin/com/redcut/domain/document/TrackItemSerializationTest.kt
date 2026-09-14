package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * What a lane's item list looks like ON DISK.
 *
 * ### Why the JSON text is asserted, and not only the round trip
 *
 * A round trip proves the model can read back what it wrote, which is true even of a discriminator nobody
 * else can read: the default one writes the subtype's fully qualified class name as the value of a `type`
 * key, and that round-trips perfectly while tying every project file to this module's package names. So
 * the bytes are asserted — `"item":"clip"`, `"item":"gap"` — because the file is a contract with the NEXT
 * build, not only with this one.
 *
 * ### Why the `Json` is built here rather than taken from the file codec
 *
 * :domain:project depends on this module and not the other way round, so `ProjectCodec` is not on this
 * tier's classpath. The three settings below ARE its settings — the same `Json` the app saves with — and
 * what is asserted here is the model's shape; the file format itself, against committed fixtures, is
 * asserted in :domain:project where the codec lives.
 */
class TrackItemSerializationTest {

    private val oneSecond = 1_000_000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** The lane this file is about: a clip, a hole, and another clip. */
    private fun laneOfClipGapClip() = Track(
        id = Track.MAIN_ID,
        kind = TrackKind.VIDEO,
        items = listOf(
            clip("v1", "s1", 0L, oneSecond),
            Gap(oneSecond),
            clip("v2", "s1", 0L, oneSecond),
        ),
    )

    @Test
    fun `a lane of clip, gap, clip says which element is which`() {
        val text = json.encodeToString(laneOfClipGapClip())

        assertThat(text).contains(""""item":"clip"""")
        assertThat(text).contains(""""item":"gap"""")
        assertThat(text).contains(""""durationUs":1000000""")
        // The defect the explicit discriminator exists to prevent: without it the value is the subtype's
        // fully qualified class name, so a package move rewrites (or fails to read) every project file.
        assertThat(text).doesNotContain("com.redcut")
    }

    @Test
    fun `the lane comes back with its items in order and its hole intact`() {
        val before = laneOfClipGapClip()

        val after = json.decodeFromString<Track>(json.encodeToString(before))

        assertThat(after).isEqualTo(before)
        assertThat(after.items).hasSize(3)
        assertThat(after.items[1]).isEqualTo(Gap(oneSecond))
        // The derived view still reads through them: that is what keeps the render compiler and the frame
        // stepping working over a lane that now holds something which is not a clip.
        assertThat(after.clips.map { it.id }).containsExactly("v1", "v2").inOrder()
    }
}
