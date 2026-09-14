package com.redcut.domain.document

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The document's upgrade rules — v1 → v2 and v2 → v3 — as rules about documents, not JSON tricks.
 *
 * The file-level half of the same story lives in :domain:project, where a real payload of each version
 * is decoded. This half is here because each rule has decisions in it — when a document is old, what
 * "nothing to move" means, and what must NOT change — and those are testable in milliseconds without a
 * file.
 *
 * What a migration must never do is lose an edit. Every test below is a different way of asking the same
 * question: after promotion, is everything that is not the clips exactly as it was? The v2 tests add the
 * question the ORDER of the two rules turns on: which files each rule is allowed to touch.
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
    fun `a document that is already v3 is left alone`() {
        // The stamp alone decides, and a document this build wrote says the CURRENT version — so a
        // project saved by this build is not migrated twice, and its lanes are not folded into one.
        val v3 = EditDocument(
            id = "doc-1",
            name = "Holiday",
            tracks = listOf(videoTrack(v1Clip("v1", 0L, oneSecond))),
        )

        assertThat(v3.promotedFromV1(listOf(v1Clip("stale", 0L, oneSecond)))).isEqualTo(v3)
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

    // --- v2 → v3: a track's flat clips become that track's items ---------------------------------

    /**
     * A v2 document as it exists in memory once the codec has read it: the stamp says 2 and every lane is
     * EMPTY, because v2's per-track `clips` key is not a field of this build's [Track]. The lanes arrive
     * separately, through the codec's own view of the file — one version on from the v1 problem above.
     */
    private fun v2Document() = EditDocument(
        schemaVersion = 2,
        id = "doc-2",
        name = "Holiday",
        sources = listOf(source("s1")),
    )

    /** A v2 lane as the codec builds it from the file: the clips it held are the lane's items. */
    private fun v2Track(vararg clips: Clip) = videoTrack(clips.toList())

    @Test
    fun `a v2 lane's clips become its items, in order, and its attributes stay at defaults`() {
        // v2 wrote no lane attributes, so the migration must not invent any: what comes out is the clip
        // list moved, and nothing else about the lane changed.
        val v2Tracks = listOf(
            v2Track(clip("c1", "s1", 0L, oneSecond), clip("c2", "s1", oneSecond, 2 * oneSecond)),
        )

        val promoted = v2Document().promotedFromV2(v2Tracks)

        assertThat(promoted.schemaVersion).isEqualTo(EditDocument.SCHEMA_VERSION)
        assertThat(promoted.tracks).isEqualTo(v2Tracks)
        assertThat(promoted.clips.map { it.id }).containsExactly("c1", "c2").inOrder()
        assertThat(promoted.tracks.single().items).hasSize(2)
        assertThat(promoted.tracks.single().volume).isEqualTo(1f)
        assertThat(promoted.tracks.single().isLocked).isFalse()
        assertThat(promoted.tracks.single().blendMode).isEqualTo(BlendMode.NORMAL)
    }

    @Test
    fun `a v2 document keeps everything that was not its clips`() {
        val before = v2Document()

        val after = before.promotedFromV2(listOf(v2Track(clip("c1", "s1", 0L, oneSecond))))

        assertThat(after.id).isEqualTo("doc-2")
        assertThat(after.name).isEqualTo("Holiday")
        assertThat(after.sources).isEqualTo(before.sources)
        assertThat(after.canvas).isEqualTo(CanvasSpec.PORTRAIT_1080)
    }

    @Test
    fun `a document that is already v3 is not migrated by the v2 rule either`() {
        val v3 = EditDocument(
            id = "doc-3",
            name = "Holiday",
            tracks = listOf(videoTrack(clip("v1", "s1", 0L, oneSecond))),
        )

        assertThat(v3.promotedFromV2(listOf(videoTrack(clip("stale", "s1", 0L, oneSecond)))))
            .isEqualTo(v3)
    }

    @Test
    fun `a v1 document is not this rule's business`() {
        // The stamp band, and why it is narrower than "anything older than this build": a v1 file has no
        // `tracks` key, so the v2 rule has nothing to move in it — but it would still stamp the version
        // forward, and the v1 rule would then find a document that looks current and leave the clips the
        // decode dropped exactly where they fell.
        val v1 = v1Document()

        val untouched = v1.promotedFromV2(emptyList())

        assertThat(untouched).isEqualTo(v1)
        assertThat(untouched.schemaVersion).isEqualTo(1)
    }

    @Test
    fun `an empty v2 document gains the stamp and no invented lane`() {
        // The stamp is brought forward either way — a file that still said 2 would be migrated again by
        // the next reader — and the lane a fresh document has is not duplicated on the way.
        val promoted = v2Document().promotedFromV2(emptyList())

        assertThat(promoted.schemaVersion).isEqualTo(EditDocument.SCHEMA_VERSION)
        assertThat(promoted.tracks).isEqualTo(listOf(Track.MAIN))
    }
}
