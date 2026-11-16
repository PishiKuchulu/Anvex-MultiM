package com.anvex.agents.core.changeset

/** Single place for every numeric limit of the engine (boundary-tested). */
object Limits {
    const val MAX_CHANGES = 2000
    const val MAX_TOTAL_BYTES = 20L * 1024 * 1024
    const val MAX_FILE_BYTES = 5L * 1024 * 1024
    /** Above this size (or non-UTF-8, or NUL) a file is merged with the binary rules. */
    const val MAX_MERGE_BYTES = 2L * 1024 * 1024
    /** Edit-distance cap of the line diff; beyond it the merge reports TOO_LARGE instead of guessing. */
    const val MAX_EDIT_DISTANCE = 2000
    /** Snapshot refuses single files above this size (fail closed, never silently skip content). */
    const val MAX_SNAPSHOT_FILE_BYTES = 20L * 1024 * 1024
}
