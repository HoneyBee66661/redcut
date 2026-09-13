package com.redcut.domain.project

import com.google.common.truth.Truth.assertThat
import com.redcut.domain.document.Clip
import com.redcut.domain.document.EditDocument
import com.redcut.domain.document.SourceRef
import com.redcut.domain.document.videoTrack
import org.junit.jupiter.api.Test

/**
 * The gallery's projection and its order.
 *
 * Two things are worth pinning here. A summary must carry enough for a TILE and no more — the whole point
 * is that the home screen never decodes a document to draw a grid — and the order must be TOTAL, because a
 * gallery whose tiles swap places between two openings looks broken in a way no error message explains.
 */
class ProjectSummaryTest {

    private val oneSecond = 1_000_000L

    private fun project(
        id: String,
        name: String = "untitled",
        updatedAtMs: Long = 1_000L,
        clips: Int = 1,
    ): SavedProject = SavedProject(
        id = id,
        name = name,
        document = EditDocument(
            id = id,
            name = name,
            sources = listOf(
                SourceRef(
                    id = "src-1",
                    uri = "content://media/1",
                    displayName = "clip.mp4",
                    durationUs = 4 * oneSecond,
                    width = 1920,
                    height = 1080,
                ),
            ),
            tracks = listOf(
                videoTrack(
                    List(clips) { index ->
                        Clip(
                            id = "clip-$index",
                            sourceId = "src-1",
                            sourceInUs = index * oneSecond,
                            sourceOutUs = (index + 1) * oneSecond,
                        )
                    },
                ),
            ),
        ),
        updatedAtMs = updatedAtMs,
    )

    @Test
    fun `a summary carries the tile's fields and counts the clips`() {
        val summary = project(
            id = "p1",
            name = "untitled 2",
            updatedAtMs = 42L,
            clips = 3,
        ).summary()

        assertThat(summary.id).isEqualTo("p1")
        assertThat(summary.name).isEqualTo("untitled 2")
        assertThat(summary.updatedAtMs).isEqualTo(42L)
        assertThat(summary.clipCount).isEqualTo(3)
    }

    @Test
    fun `an empty project summarises as zero clips rather than as nothing`() {
        // A project the user made and then removed everything from is still a project, and a tile that says
        // "0 clips" is honest. Dropping it from the list would look like the editor had lost it.
        val empty = SavedProject(
            id = "p0",
            name = "untitled",
            document = EditDocument(id = "p0", name = "untitled"),
            updatedAtMs = 1L,
        )

        assertThat(empty.summary().clipCount).isEqualTo(0)
        assertThat(summariesNewestFirst(listOf(empty.summary()))).hasSize(1)
    }

    @Test
    fun `the newest project comes first`() {
        val older = project(id = "old", name = "untitled", updatedAtMs = 1_000L).summary()
        val newer = project(id = "new", name = "untitled 2", updatedAtMs = 2_000L).summary()

        assertThat(
            summariesNewestFirst(listOf(older, newer)),
        ).containsExactly(newer, older).inOrder()
    }

    @Test
    fun `projects touched at the same millisecond keep a stable order by name`() {
        // The order has to be TOTAL. A pair of projects saved in the same millisecond is not exotic — two
        // imports in a row will do it — and leaving their order to the filesystem means the gallery can
        // rearrange itself between one opening and the next.
        val b = project(id = "b", name = "untitled 2", updatedAtMs = 5L).summary()
        val a = project(id = "a", name = "untitled", updatedAtMs = 5L).summary()

        assertThat(summariesNewestFirst(listOf(b, a)).map { it.name })
            .containsExactly("untitled", "untitled 2").inOrder()
        assertThat(summariesNewestFirst(listOf(a, b)).map { it.name })
            .containsExactly("untitled", "untitled 2").inOrder()
    }
}
