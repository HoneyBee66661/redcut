package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The playhead→frame mapping the preview is built on.
 *
 * This is the "correct" in the phase's exit criterion ("scrub with correct preview"), and every case
 * below is a way a naive implementation would be wrong in a way nobody notices until the edit is
 * wrong: an offset trim, a speed change, a reversed clip, a second clip on the timeline.
 */
class PreviewTargetTest {

    private val oneSecond = 1_000_000L

    private fun source(id: String = "src-1", uri: String = "content://media/$id") = SourceRef(
        id = id,
        uri = uri,
        displayName = "$id.mp4",
        durationUs = 60 * oneSecond,
        width = 1920,
        height = 1080,
    )

    private fun clip(
        id: String = "clip-a",
        sourceInUs: Long = 0L,
        sourceOutUs: Long = 4 * oneSecond,
        sourceId: String = "src-1",
        speed: Float = 1f,
        reverse: Boolean = false,
    ) = Clip(
        id = id,
        sourceId = sourceId,
        sourceInUs = sourceInUs,
        sourceOutUs = sourceOutUs,
        speed = speed,
        reverse = reverse,
    )

    private fun document(vararg clips: Clip, sources: List<SourceRef> = listOf(source())) =
        EditDocument(id = "doc", name = "Doc", sources = sources, clips = clips.toList())

    @Test
    fun `an untrimmed clip shows its own time`() {
        val target = document(clip()).previewTargetAt(oneSecond)

        assertThat(target).isEqualTo(
            PreviewTarget("clip-a", "src-1", "content://media/src-1", oneSecond),
        )
    }

    @Test
    fun `a trimmed clip shows the source time its offset maps to, not the timeline time`() {
        // The clip reads 10 s..14 s of its file. Two seconds into the clip is 12 s of the file —
        // showing 2 s here is the off-by-the-trim bug, and it looks plausible on screen.
        val doc = document(clip(sourceInUs = 10 * oneSecond, sourceOutUs = 14 * oneSecond))

        assertThat(doc.previewTargetAt(2 * oneSecond)?.sourceTimeUs).isEqualTo(12 * oneSecond)
    }

    @Test
    fun `a sped-up clip consumes its source twice as fast`() {
        // At 2x, one second of timeline is two seconds of file: seek to 2 s of the file at 1 s.
        val doc = document(clip(sourceOutUs = 8 * oneSecond, speed = 2f))

        assertThat(doc.previewTargetAt(oneSecond)?.sourceTimeUs).isEqualTo(2 * oneSecond)
    }

    @Test
    fun `a reversed clip counts DOWN from its out point`() {
        // Reversed, the clip starts at the END of its range: 1 s in is 3 s of the file.
        val doc = document(clip(sourceInUs = 0L, sourceOutUs = 4 * oneSecond, reverse = true))

        assertThat(doc.previewTargetAt(oneSecond)?.sourceTimeUs).isEqualTo(3 * oneSecond)
    }

    @Test
    fun `a trimmed, sped-up, reversed clip is all three at once`() {
        // 10 s..14 s of the file, at 2x, reversed: the clip is 2 s of timeline, and its first frame is
        // the file's 14 s. Half a second in is 13 s — the four rules above composed, which is the case
        // a hand-written mapping gets wrong.
        val doc = document(
            clip(
                sourceInUs = 10 * oneSecond,
                sourceOutUs = 14 * oneSecond,
                speed = 2f,
                reverse = true,
            ),
        )

        assertThat(doc.previewTargetAt(0L)?.sourceTimeUs).isEqualTo(14 * oneSecond)
        assertThat(doc.previewTargetAt(500_000L)?.sourceTimeUs).isEqualTo(13 * oneSecond)
    }

    @Test
    fun `the second clip on the timeline reads its own file from its own start`() {
        val doc = document(
            clip(id = "clip-a", sourceOutUs = 4 * oneSecond),
            clip(
                id = "clip-b",
                sourceInUs = 8 * oneSecond,
                sourceOutUs = 12 * oneSecond,
                sourceId = "src-2",
            ),
            sources = listOf(source(), source("src-2")),
        )

        // Four seconds in is the START of clip-b, which reads from 8 s of src-2 — the second source,
        // not the first one the document happens to list.
        assertThat(doc.previewTargetAt(4 * oneSecond)).isEqualTo(
            PreviewTarget("clip-b", "src-2", "content://media/src-2", 8 * oneSecond),
        )
    }

    @Test
    fun `past the end of the timeline there is nothing to show`() {
        val doc = document(clip())

        assertThat(doc.previewTargetAt(4 * oneSecond)).isNull()
        assertThat(doc.previewTargetAt(99 * oneSecond)).isNull()
    }

    @Test
    fun `a clip whose source is missing shows nothing rather than a guessed uri`() {
        // Not hypothetical: a document restored from storage can name a source that is no longer in it,
        // and a preview that made up a uri would show the wrong file rather than an empty stage.
        val doc = EditDocument(
            id = "doc",
            name = "Doc",
            sources = emptyList(),
            clips = listOf(clip()),
        )

        assertThat(doc.previewTargetAt(oneSecond)).isNull()
    }
}
