package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The v1 → v2 migration, as a rule about documents rather than as a JSON trick.
 *
 * The file-level half of the same story lives in :domain:project, where a real v1 payload is decoded.
 * This half is here because the rule has decisions in it — when a document is old, what "nothing to
 * move" means, and what must NOT change — and those are testable in milliseconds without a file.
 *
 * What the migration must never do is lose an edit. Every test below is a different way of asking the
 * same question: after promotion, is everything that is not the clips exactly as it was?
 */
class DocumentMigrationTest {

    private val oneSecond = 1_000_000L

    /** A clip as v1 stored it: no track, because v1 had none. */
    private fun v1Clip(id: String, inUs: Long, outUs: Long, sourceId: String = "s1") = Clip(
        id = id,
        sourceId = sourceId,
        sourceInUs = inUs,
        sourceOutUs = outUs,
    )

    /**
     * A v1 document as it exists in memory once the codec has read it: the version says 1 and there are
     * no clips anywhere, because v1's flat `clips` key is not a field of this build's document. The
     * clips arrive separately — that is the shape of the problem the migration solves.
     */
    private fun v1Document(effects: List<AppliedEffect> = emptyList()) = EditDocument(
        schemaVersion = 1,
        id = "doc-1",
        name = "Holiday",
        sources = listOf(source("s1")),
        effects = effects,
        canvas = CanvasSpec.PORTRAIT_720,
        createdAtMs = 1_000L,
        modifiedAtMs = 2_000L,
        revision = 7L,
    )

    @Test
    fun `a v1 document's clips become one video track holding them, in order`() {
        val clips = listOf(
            v1Clip("c1", 0L, 2 * oneSecond),
            v1Clip("c2", 2 * oneSecond, 5 * oneSecond),
            v1Clip("c3", 5 * oneSecond, 6 * oneSecond),
        )

        val promoted = v1Document().promotedFromV1(clips)

        assertThat(promoted.tracks).hasSize(1)
        assertThat(promoted.tracks.single().id).isEqualTo(Track.MAIN_ID)
        assertThat(promoted.tracks.single().kind).isEqualTo(TrackKind.VIDEO)
        assertThat(promoted.tracks.single().clips).isEqualTo(clips)
        assertThat(promoted.clips.map { it.id }).containsExactly("c1", "c2", "c3").inOrder()
    }

    @Test
    fun `nothing but the clips and the stamp changes`() {
        // The whole claim of a migration: the work the user did is still there. Sources, effects,
        // canvas, name, the timestamps and the revision are copied through untouched — the promotion
        // replaces ONE field, and this is the assertion that it stayed that way.
        val effect = AppliedEffect.Adjust(
            id = "e1",
            scope = EffectScope.Clip("c1"),
            timeRange = TimeRange(0L, oneSecond),
            spec = ColorAdjustSpec(brightness = 0.2f),
        )
        val before = v1Document(effects = listOf(effect))

        val after = before.promotedFromV1(before.clips)

        assertThat(after.schemaVersion).isEqualTo(EditDocument.SCHEMA_VERSION)
        assertThat(after.id).isEqualTo("doc-1")
        assertThat(after.name).isEqualTo("Holiday")
        assertThat(after.sources).isEqualTo(before.sources)
        assertThat(after.effects).isEqualTo(listOf(effect))
        assertThat(after.canvas).isEqualTo(CanvasSpec.PORTRAIT_720)
        assertThat(after.createdAtMs).isEqualTo(1_000L)
        assertThat(after.modifiedAtMs).isEqualTo(2_000L)
        assertThat(after.revision).isEqualTo(7L)
    }

    @Test
    fun `an effect scoped to a v1 clip still names a clip that exists`() {
        // The invariant effects depend on (spec §7.2): promotion moves the clips, so the ids an effect
        // was scoped to must still resolve. This is the one that would break silently — an effect
        // pointing at a clip no render pass can find simply never draws.
        val clip = v1Clip("c1", 0L, 2 * oneSecond)
        val effect = AppliedEffect.Text(
            id = "e1",
            scope = EffectScope.Clip("c1"),
            timeRange = TimeRange(0L, oneSecond),
            spec = TextSpec(content = "caption"),
        )

        val promoted = v1Document(effects = listOf(effect))
            .promotedFromV1(listOf(clip))

        assertThat(promoted.clipById("c1")).isEqualTo(clip)
        assertThat(promoted.isRenderable()).isTrue()
        assertThat(promoted.effects.single().scope).isEqualTo(EffectScope.Clip("c1"))
    }

    @Test
    fun `a document that is already v2 is left alone`() {
        // The stamp alone decides, and a v2 document says 2 — so a project saved by this build is not
        // migrated twice, and its lanes are not folded into one.
        val v2 = EditDocument(
            id = "doc-1",
            name = "Holiday",
            tracks = listOf(videoTrack(v1Clip("v1", 0L, oneSecond))),
        )

        assertThat(v2.promotedFromV1(listOf(v1Clip("stale", 0L, oneSecond)))).isEqualTo(v2)
    }

    @Test
    fun `an old stamp on a document that already holds its clips keeps them`() {
        // Belt and braces against a file written between the two formats: the version says "old" and
        // the tracks say "already moved". Duplicating the clips would be worse than doing nothing.
        val halfMoved = EditDocument(
            schemaVersion = 1,
            id = "doc-1",
            name = "Holiday",
            tracks = listOf(videoTrack(v1Clip("v1", 0L, oneSecond))),
        )

        val promoted = halfMoved.promotedFromV1(listOf(v1Clip("stale", 0L, oneSecond)))

        assertThat(promoted.clips.map { it.id }).containsExactly("v1")
        assertThat(promoted.schemaVersion).isEqualTo(EditDocument.SCHEMA_VERSION)
    }

    @Test
    fun `an empty v1 document gains a stamp and no empty lane`() {
        // A project the user made and then removed everything from is still a project: it opens with
        // the video lane a fresh document has, and nothing is invented to fill it.
        val empty = v1Document()

        val promoted = empty.promotedFromV1(emptyList())

        assertThat(promoted.schemaVersion).isEqualTo(EditDocument.SCHEMA_VERSION)
        assertThat(promoted.tracks).isEqualTo(listOf(Track.MAIN))
        assertThat(promoted.clips).isEmpty()
    }
}
