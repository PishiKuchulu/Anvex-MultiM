package com.anvex.agents.core

import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentLimits
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.CredentialGuard
import com.anvex.agents.core.domain.Grant
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTest {
    private fun fails(block: () -> Unit) { try { block(); throw AssertionError("expected IllegalArgumentException") } catch (_: IllegalArgumentException) { } }

    @Test fun grantDefaultsAreLeastPrivilege() {
        val g = Grant(); assertEquals(listOf("**"), g.read); assertTrue(g.write.isEmpty()); assertFalse(g.execute); assertFalse(g.approve)
    }
    @Test fun grantRejectsBadGlobs() { fails { Grant(write = listOf("../x")) }; fails { Grant(read = listOf("")) } }
    @Test fun limitsMustBePositive() { fails { AgentLimits(maxTurns = 0) }; fails { AgentLimits(maxTokens = -1) }; fails { AgentLimits(timeoutMs = 0) } }

    @Test fun profileValidation() {
        fails { AgentProfile(AgentId("a"), "", "r", "p", "m") }
        fails { AgentProfile(AgentId("a"), "n", "r", "", "m") }
        fails { AgentProfile(AgentId("a"), "n", "r", "p", "m", temperature = 3.0) }
        AgentProfile(AgentId("a"), "n", "r", "p", "m", temperature = 0.0)
        AgentProfile(AgentId("a"), "n", "r", "p", "m", temperature = 2.0)
    }

    @Test fun profileRefusesCredentialsInParamsAndText() {
        fails { AgentProfile(AgentId("a"), "n", "r", "p", "m", params = mapOf("apiKey" to "x")) }
        fails { AgentProfile(AgentId("a"), "n", "r", "p", "m", params = mapOf("Authorization" to "x")) }
        fails { AgentProfile(AgentId("a"), "n", "r", "p", "m", params = mapOf("note" to "sk-abcdefghijklmnopqrstuvwxyz")) }
        fails { AgentProfile(AgentId("a"), "n", "use key sk-abcdefghijklmnopqrstuvwxyz", "p", "m") }
        AgentProfile(AgentId("a"), "n", "r", "p", "m", params = mapOf("top_p" to "0.9", "max_tokens" to "100"))
    }

    @Test fun credentialGuardDetectsCommonShapes() {
        val secrets = listOf(
            "sk-proj-abcdefghijklmnop1234", "AIzaSyA1234567890abcdefghijklmnop", "ghp_abcdefghijklmnopqrstuvwxyz0123",
            "xoxb-1234567890-abcdef", "-----BEGIN RSA PRIVATE KEY-----", "Bearer abcdefghijklmnopqrstuvwxyz012345",
        )
        for (s in secrets) { assertTrue("value: $s", CredentialGuard.looksLikeSecretValue(s)); assertFalse(CredentialGuard.scrub("x $s y").contains(s)) }
        for (k in listOf("apiKey", "API_KEY", "x-api-key", "client_secret", "accessToken", "PASSWORD", "authorization", "private key", "token", "GITHUB_TOKEN", "x-auth-token", "refresh_token", "Bearer"))
            assertTrue("key: $k", CredentialGuard.isCredentialKey(k))
        for (k in listOf("model", "temperature", "max_tokens", "maxTokens", "max_output_tokens", "tokenLimit", "name", "top_p", "stop_sequences", "thinking_budget_tokens"))
            assertFalse("legitimate parameter flagged: $k", CredentialGuard.isCredentialKey(k))
        assertFalse(CredentialGuard.looksLikeSecretValue("just a normal sentence about tokens in a compiler"))
        assertEquals(listOf("k1"), CredentialGuard.violations(mapOf("k1" to "sk-abcdefghijklmnopqrstuvwx", "k2" to "fine")))
    }

    @Test fun taskSpecValidation() {
        val a = listOf(AgentId("a"))
        fails { TaskSpec(TaskId("t"), "", "acc", assignees = a) }
        fails { TaskSpec(TaskId("t"), "x", "acc", assignees = emptyList()) }
        fails { TaskSpec(TaskId("t"), "x", "acc", assignees = listOf(AgentId("a"), AgentId("a"))) }
        fails { TaskSpec(TaskId("t"), "x", "acc", deps = setOf(TaskId("t")), assignees = a) }
        fails { TaskSpec(TaskId("t"), "x", "acc", claims = listOf("../etc"), assignees = a) }
        assertTrue(TaskSpec(TaskId("t"), "x", "acc", assignees = listOf(AgentId("a"), AgentId("b"))).competing)
        fails { TaskId(" ") }; fails { AgentId("") }
    }
}
