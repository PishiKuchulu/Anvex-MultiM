package com.anvex.agents.core

import com.anvex.agents.core.assurance.PlanParse
import com.anvex.agents.core.assurance.PlanParser
import com.anvex.agents.core.assurance.UserApproval
import com.anvex.agents.core.assurance.VerdictParse
import com.anvex.agents.core.assurance.VerdictParser
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.GateState
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.ports.Digests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssuranceTest {
    private val t = TaskId("t-7")
    private fun verdict(json: String) = VerdictParser.parse(json, t)
    private fun invalid(json: String): List<String> { val r = verdict(json); assertTrue("expected invalid: $json -> $r", r is VerdictParse.Invalid); return (r as VerdictParse.Invalid).reasons }

    // ---------------------------------------------------------------- reviewer verdicts
    @Test fun validVerdicts() {
        val ok = verdict("""{"task":"t-7","decision":"approve"}""") as VerdictParse.Valid; assertTrue(ok.verdict.approve); assertTrue(ok.verdict.findings.isEmpty())
        val rej = verdict("""{"task":"t-7","decision":"reject","findings":[{"file":"src/a.kt","line":40,"text":"null check missing"},{"text":"general remark"}]}""") as VerdictParse.Valid
        assertFalse(rej.verdict.approve); assertEquals(2, rej.verdict.findings.size); assertEquals("src/a.kt", rej.verdict.findings[0].file!!.value); assertEquals(40, rej.verdict.findings[0].line); assertNull(rej.verdict.findings[1].file)
        assertTrue(verdict("""{"task":"t-7","decision":"approve","findings":null}""") is VerdictParse.Valid)
        assertTrue("approval may still carry informational findings", verdict("""{"task":"t-7","decision":"approve","findings":[{"text":"nit"}]}""") is VerdictParse.Valid)
    }
    @Test fun invalidVerdicts() {
        assertTrue(invalid("not json")[0].contains("not valid JSON")); invalid("[]"); invalid("""{"decision":"approve"}"""); invalid("""{"task":"other","decision":"approve"}""")
        invalid("""{"task":"t-7","decision":"maybe"}"""); invalid("""{"task":"t-7","decision":"APPROVE"}"""); invalid("""{"task":"t-7","decision":true}""")
        assertTrue("a rejection needs findings", invalid("""{"task":"t-7","decision":"reject"}""")[0].contains("actionable")); invalid("""{"task":"t-7","decision":"reject","findings":[]}""")
        assertTrue(invalid("""{"task":"t-7","decision":"approve","admin":true}""")[0].contains("unknown field")); invalid("""{"task":"t-7","decision":"approve","findings":"x"}""")
        invalid("""{"task":"t-7","decision":"reject","findings":[{"file":"../../etc/passwd","text":"x"}]}"""); invalid("""{"task":"t-7","decision":"reject","findings":[{"file":"a.kt","line":0,"text":"x"}]}""")
        invalid("""{"task":"t-7","decision":"reject","findings":[{"line":1.5,"text":"x"}]}"""); invalid("""{"task":"t-7","decision":"reject","findings":[{"line":"3","text":"x"}]}""")
        invalid("""{"task":"t-7","decision":"reject","findings":[{"text":""}]}"""); invalid("""{"task":"t-7","decision":"reject","findings":["str"]}""")
        invalid("""{"task":"t-7","decision":"reject","findings":[""" + (1..51).joinToString(",") { """{"text":"f$it"}""" } + "]}")
        invalid("""{"task":"t-7","task":"t-7","decision":"approve"}""")
    }
    @Test fun findingTextIsScrubbedAndTruncated() {
        val v = verdict("""{"task":"t-7","decision":"reject","findings":[{"text":"key leaked: sk-abcdefghijklmnopqrstuvwxyz0123 ${"x".repeat(3000)}"}]}""") as VerdictParse.Valid
        val txt = v.verdict.findings.single().text; assertFalse(txt.contains("sk-abcdefghijklmnop")); assertTrue(txt.contains("[REDACTED]")); assertTrue(txt.length <= 2000)
    }

    // ---------------------------------------------------------------- manager plans
    private val known = setOf(AgentId("a"), AgentId("b"), AgentId("c"))
    private val goodPlan = """{"tasks":[
        {"id":"t1","title":"Design API","acceptance":"Interfaces documented","assignees":["a"],"claims":["docs/**"]},
        {"id":"t2","title":"Implement core","acceptance":"Unit tests pass","deps":["t1"],"assignees":["b","c"],"claims":["core/**"],"priority":3},
        {"id":"t3","title":"UI","acceptance":"Screens render","deps":["t1"],"assignees":["c"],"requiresReview":true}]}"""
    private fun plan(json: String) = PlanParser.parse(json, known)
    private fun planInvalid(json: String): List<String> { val r = plan(json); assertTrue("expected invalid: ${json.take(120)} -> $r", r is PlanParse.Invalid); return (r as PlanParse.Invalid).reasons }

    @Test fun aValidPlanIsInertWithoutTheUsersApprovalForThatExactPlan() {
        val p = (plan(goodPlan) as PlanParse.Valid).plan
        assertEquals(listOf("t1", "t2", "t3"), p.tasks.map { it.id.value }); assertTrue("review can never be switched off by a plan", p.tasks.all { it.requiresReview })
        assertTrue(p.tasks[1].competing); assertEquals(3, p.tasks[1].priority)
        assertEquals(p.digest, (plan(goodPlan) as PlanParse.Valid).plan.digest)
        val other = (plan(goodPlan.replace("Design API", "Design APIs")) as PlanParse.Valid).plan; assertNotEquals(p.digest, other.digest)
        try { p.toTaskSpecs(UserApproval(other.digest)); throw AssertionError("approval for another plan accepted") } catch (_: IllegalArgumentException) { }
        try { p.toTaskSpecs(UserApproval(Digests.sha256(ByteArray(0)))); throw AssertionError("random approval accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(p.tasks, p.toTaskSpecs(UserApproval(p.digest)))
    }

    @Test fun invalidPlans() {
        planInvalid("nope"); planInvalid("[]"); planInvalid("""{"tasks":[]}"""); planInvalid("""{"tasks":[],"extra":1}"""); planInvalid("""{"tasks":"x"}""")
        fun one(extra: String) = """{"tasks":[{"id":"t1","title":"T","acceptance":"A","assignees":["a"]$extra}]}"""
        assertTrue(plan(one("")) is PlanParse.Valid)
        assertTrue(planInvalid(one(""","requiresReview":false"""))[0].contains("disable mandatory review")); planInvalid(one(""","requiresReview":"no""""))
        assertTrue(plan(one(""","requiresReview":true""")) is PlanParse.Valid)
        planInvalid(one(""","admin":true""")); planInvalid(one(""","deps":["t1"]""")); planInvalid(one(""","deps":["ghost"]""")); planInvalid(one(""","claims":["../x"]""")); planInvalid(one(""","claims":"app/**""""))
        planInvalid(one(""","priority":1000""")); planInvalid(one(""","priority":1.5"""))
        planInvalid("""{"tasks":[{"id":"t1","title":"T","acceptance":"A","assignees":["nobody"]}]}"""); planInvalid("""{"tasks":[{"id":"t1","title":"T","acceptance":"A","assignees":[]}]}""")
        planInvalid("""{"tasks":[{"id":"t1","title":"T","acceptance":"A","assignees":["a","a"]}]}"""); planInvalid("""{"tasks":[{"id":"t1","title":"T","acceptance":"A"}]}""")
        planInvalid("""{"tasks":[{"id":"bad id","title":"T","acceptance":"A","assignees":["a"]}]}"""); planInvalid("""{"tasks":[{"id":"t1","acceptance":"A","assignees":["a"]}]}""")
        planInvalid("""{"tasks":[{"id":"t1","title":"${"x".repeat(201)}","acceptance":"A","assignees":["a"]}]}""")
        planInvalid("""{"tasks":[{"id":"t1","title":"T","acceptance":"A","assignees":["a"]},{"id":"t1","title":"T2","acceptance":"A","assignees":["a"]}]}""")
        planInvalid("""{"tasks":[{"id":"t2","title":"T","acceptance":"A","deps":["t1"],"assignees":["a"]},{"id":"t1","title":"T","acceptance":"A","assignees":["a"]}]}""")   // forward dependency => no cycles possible
        planInvalid("""{"tasks":[{"id":"t1","title":"use sk-abcdefghijklmnopqrstuvwxyz0123","acceptance":"A","assignees":["a"]}]}""")
        planInvalid("""{"tasks":[""" + (1..51).joinToString(",") { """{"id":"t$it","title":"T","acceptance":"A","assignees":["a"]}""" } + "]}")
    }
    @Test fun aFiftyTaskPlanIsAccepted() {
        assertTrue(plan("""{"tasks":[""" + (1..50).joinToString(",") { """{"id":"t$it","title":"T","acceptance":"A","assignees":["a"]}""" } + "]}") is PlanParse.Valid)
    }

    @Test fun anApprovedPlanDrivesTheSchedulerAndEveryTaskStillNeedsAnIndependentReview() {
        val p = (plan(goodPlan) as PlanParse.Valid).plan
        val sim = Sim(agents = defaultAgents()); for (s in p.toTaskSpecs(UserApproval(p.digest))) sim.add(s)
        assertEquals(listOf("t1:a"), sim.allStarts())
        sim.runToReview("t1"); assertEquals(GateState.Required, sim.node("t1").gate); sim.approveBy("t1"); sim.applied("t1")
        assertEquals("t2 competes (b,c) and t3 starts after t1 completed", setOf("t1:a", "t2:b", "t2:c", "t3:c"), sim.allStarts().toSet())
    }
}
