package com.redcut.domain.document

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * One element of a [Track]'s ordered contents: a [Clip], or a [Gap] where nothing plays.
 *
 * ### Why the contents are a polymorphic list, and why it says so out loud
 *
 * A lane used to hold `List<Clip>`, which cannot express a hole: the only way to say "nothing here for
 * two seconds" was to leave a longer distance between two clips, a subtraction the reader has to perform
 * and the writer has to keep true. An item list makes the hole an ELEMENT (see [Gap] for why that
 * matters), and a serialized list of a sealed type has to say which element IS which.
 *
 * That discriminator is why the annotation is here rather than omitted. kotlinx's default is a `type`
 * key holding the subtype's fully qualified class name, so every element of every track in every project
 * file would carry `com.redcut.domain.document.Clip` — a package move or a rename would then rewrite, or
 * refuse to read, every project on disk. `item` holding `clip` or `gap` is the same fact in a form that
 * survives a refactor.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("item")
sealed interface TrackItem

/**
 * A hole of [durationUs] in a lane: no source, no identity, nothing drawn.
 *
 * ### Why a hole is an element rather than a subtraction
 *
 * Positions are derived by prefix-summing a track's items, so every element has to account for its OWN
 * length. A hole expressed as "the space between the clip before and the clip after" is a length nobody
 * stores: it would be re-derived from absolute times on every read, and the first ripple edit that moved
 * one neighbour without the other would silently change it. As an element it is one number a command can
 * insert, move or remove like any other — at the cost of a discriminator in the file.
 *
 * There is no id, because a gap is not a thing the user named, selected or linked, and giving it one
 * would invite a command to address it. There is no source, because a hole is the absence of one.
 */
@Serializable
@SerialName("gap")
data class Gap(val durationUs: Long) : TrackItem {
    init {
        require(durationUs > 0) { "a gap has a duration" }
    }
}
