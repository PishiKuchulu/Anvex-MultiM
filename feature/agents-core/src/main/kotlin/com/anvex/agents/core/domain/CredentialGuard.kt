package com.anvex.agents.core.domain

/**
 * Credential denylist used by profile validation, event/audit payload scrubbing and persistence
 * (ADR-018, acceptance creds_absent). Mirrors the intent of the host's DurableTaskEngine payload check
 * without depending on it.
 */
object CredentialGuard {
    // Substrings that make a key credential-like on their own.
    private val KEY_PARTS = listOf("apikey", "secret", "password", "passwd", "authorization", "bearer", "credential", "privatekey")
    private val VALUE_PATTERNS = listOf(
        Regex("""sk-[A-Za-z0-9_\-]{16,}"""),
        Regex("""AIza[0-9A-Za-z_\-]{20,}"""),
        Regex("""ghp_[A-Za-z0-9]{20,}"""),
        Regex("""xox[abp]-[A-Za-z0-9\-]{10,}"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
        Regex("""(?i)bearer\s+[A-Za-z0-9._\-]{20,}"""),
    )

    /**
     * "token" needs care: `accessToken`, `auth-token` and `GITHUB_TOKEN` are credentials, while
     * `max_tokens`, `max_output_tokens` and `tokenLimit` are legitimate model parameters (token counts).
     * Rule: after removing separators the key is a credential when it ends with the singular "token".
     */
    fun isCredentialKey(key: String): Boolean {
        val k = key.lowercase().filter { it.isLetterOrDigit() }
        return KEY_PARTS.any { k.contains(it) } || k.endsWith("token")
    }

    fun looksLikeSecretValue(value: String): Boolean = VALUE_PATTERNS.any { it.containsMatchIn(value) }

    /** Names of offending entries (keys only, never values). */
    fun violations(fields: Map<String, String>): List<String> =
        fields.entries.filter { isCredentialKey(it.key) || looksLikeSecretValue(it.value) }.map { it.key }

    fun requireClean(fields: Map<String, String>, what: String) {
        val v = violations(fields)
        require(v.isEmpty()) { "$what contains credential-like entries: ${v.joinToString()}" }
    }

    /** Replaces credential-like values by a marker; returns the scrubbed text. */
    fun scrub(text: String): String = VALUE_PATTERNS.fold(text) { acc, rx -> rx.replace(acc, "[REDACTED]") }
}
