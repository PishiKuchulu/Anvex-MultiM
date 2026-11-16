package com.anvex.agents.core.changeset

import com.anvex.agents.core.path.Glob
import com.anvex.agents.core.path.RelPath

/** Paths that are never snapshotted, diffed or integrated (build output, VCS data, IDE files). */
class IgnoreRules(val patterns: List<String>) {
    init { patterns.forEach { require(Glob.isValid(it)) { "invalid ignore pattern '$it'" } } }
    fun isIgnored(path: RelPath): Boolean = Glob.matchesAny(patterns, path)
    companion object {
        val DEFAULT = IgnoreRules(listOf(".git/**", "**/build/**", "build/**", ".gradle/**", "**/node_modules/**", ".idea/**", "**/*.iml", "**/.DS_Store", ".kotlin/**", "**/.*.tmp-*"))   // last: leftovers of the host's atomic tmp+rename writes
        val NONE = IgnoreRules(emptyList())
    }
}
