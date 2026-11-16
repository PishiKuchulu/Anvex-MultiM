package com.anvex.agents.core

import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.Grant
import com.anvex.agents.core.registry.AgentRegistry
import com.anvex.agents.core.registry.RoleTemplate
import com.anvex.agents.core.scheduler.Scheduler
import com.anvex.agents.core.scheduler.SchedulerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryTest {
    @Test fun defaultRolesCoverTheSpecification() {
        val r = AgentRegistry.withDefaults()
        for (id in listOf("software-architect", "senior-developer", "frontend-developer", "backend-developer", "qa-engineer", "code-reviewer", "security-auditor", "documentation-engineer", "performance-engineer", "build-release-engineer"))
            assertTrue("role $id", r.role(id) != null)
        assertEquals(10, r.roleIds().size)
    }
    @Test fun reviewersCannotWriteAndDevelopersCannotApprove() {
        val r = AgentRegistry.withDefaults()
        val rev = r.instantiate("code-reviewer", AgentId("x"), "X", "p", "m"); assertTrue(rev.grant.approve); assertTrue(rev.grant.write.isEmpty())
        val dev = r.instantiate("senior-developer", AgentId("y"), "Y", "p", "m"); assertFalse(dev.grant.approve); assertFalse(dev.grant.execute)
    }
    @Test fun unknownRoleAndDuplicateAreRejected() {
        val r = AgentRegistry.withDefaults()
        try { r.instantiate("nope", AgentId("x"), "X", "p", "m"); throw AssertionError("expected") } catch (_: IllegalArgumentException) { }
        try { r.register(r.role("qa-engineer")!!); throw AssertionError("expected") } catch (_: IllegalArgumentException) { }
        try { RoleTemplate("Bad Id!", "t", "i", Grant()); throw AssertionError("expected") } catch (_: IllegalArgumentException) { }
        assertNull(r.role("nope"))
    }

    /** Acceptance extensibility: a brand new role is plain data; scheduler/policy/integrator code is not touched. */
    @Test fun newRoleWorksWithoutChangingTheEngine() {
        val r = AgentRegistry.withDefaults().register(RoleTemplate("chaos-tester", "Chaos Tester", "Break things safely.", Grant(write = listOf("tests/**")), setOf("chaos")))
        val agent = r.instantiate("chaos-tester", AgentId("ct"), "CT", "prov", "model")
        val sim = Sim(SchedulerConfig(), listOf(agent) + defaultAgents())
        sim.add(task("t1", assignees = listOf("ct")))
        assertEquals(listOf("t1:ct"), sim.allStarts())
        assertEquals(11, r.roleIds().size)
        assertEquals(setOf("chaos"), agent.capabilities)
        assertTrue(Scheduler.initial(SchedulerConfig(), listOf(agent)).agents.containsKey(AgentId("ct")))
    }
}
