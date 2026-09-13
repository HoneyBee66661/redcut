package com.redcut.domain.project

import kotlinx.serialization.Serializable

/**
 * A project as a LIST needs it: enough to draw a tile, and nothing more.
 *
 * ### Why this is not just `SavedProject`
 *
 * The home screen draws a gallery of tiles — name, a thumbnail of the first clip, when it was last touched.
 * Handing it whole documents would mean decoding every clip of every project to draw a grid of names, and
 * the cost grows with the projects the user has rather than with the ones they are looking at. A summary is
 * what the screen needs; the document is what OPENING a project needs, and those are two different loads.
 *
 * [clipCount] rather than a list of ids, for the same reason: a tile says "3 clips", it does not enumerate
 * them. The thumbnail a tile shows comes from the project's first clip, which is why the id is here — the
 * screen asks the media layer for one frame by source id.
 */
@Serializable
data class ProjectSummary(
    val id: String,
    val name: String,
    val updatedAtMs: Long,
    val clipCount: Int,
)

/** The summary of a project: the projection the home screen reads. */
fun SavedProject.summary(): ProjectSummary = ProjectSummary(
    id = id,
    name = name,
    updatedAtMs = updatedAtMs,
    clipCount = document.clips.size,
)

/**
 * The summaries in the order a gallery shows them: most recently touched first.
 *
 * Sorting HERE rather than in the store, because the order is a decision about the screen — a future
 * "sort by name" is a change to this function and nothing else, and the store stays a store. Ties are
 * broken by name so the order is total: two projects saved in the same millisecond (a test, or a quick
 * pair of imports) must not come back in whatever order the filesystem happened to list them.
 */
fun summariesNewestFirst(summaries: List<ProjectSummary>): List<ProjectSummary> =
    summaries.sortedWith(compareByDescending<ProjectSummary> { it.updatedAtMs }.thenBy { it.name })
