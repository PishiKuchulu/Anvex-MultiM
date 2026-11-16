package com.anvex.agents.core.registry

import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentLimits
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.Grant

/** A reusable role. Adding a role is data: no scheduler, policy or integrator code changes (requirement extensible). */
data class RoleTemplate(
    val id: String,
    val title: String,
    val instructions: String,
    val defaultGrant: Grant,
    val capabilities: Set<String> = emptySet(),
) { init { require(id.matches(Regex("[a-z][a-z0-9\\-]{1,40}"))) { "invalid role id '$id'" } } }

class AgentRegistry private constructor(private val roles: Map<String, RoleTemplate>) {
    fun roleIds(): List<String> = roles.keys.sorted()
    fun role(id: String): RoleTemplate? = roles[id]

    fun register(template: RoleTemplate): AgentRegistry {
        require(template.id !in roles) { "role '${template.id}' already registered" }
        return AgentRegistry(roles + (template.id to template))
    }

    fun instantiate(
        roleId: String, id: AgentId, name: String, providerRef: String, model: String,
        temperature: Double? = null, params: Map<String, String> = emptyMap(),
        grant: Grant? = null, limits: AgentLimits = AgentLimits(),
    ): AgentProfile {
        val t = roles[roleId] ?: throw IllegalArgumentException("unknown role '$roleId'")
        return AgentProfile(id, name, t.instructions, providerRef, model, temperature, params, grant ?: t.defaultGrant, limits, t.capabilities)
    }

    companion object {
        private val DEV = Grant(read = listOf("**"), write = listOf("**"))
        private val REVIEW = Grant(read = listOf("**"), write = emptyList(), approve = true)

        /** The roles named in the specification; configurable, not a fixed team. */
        fun withDefaults(): AgentRegistry = AgentRegistry(emptyMap())
            .register(RoleTemplate("software-architect", "Software Architect", "Design structure and interfaces; do not implement features.", Grant(read = listOf("**"), write = listOf("docs/**"))))
            .register(RoleTemplate("senior-developer", "Senior Developer", "Implement the task completely with tests.", DEV, setOf("code")))
            .register(RoleTemplate("frontend-developer", "Frontend / UI Developer", "Implement UI changes following the project design system.", DEV, setOf("code", "ui")))
            .register(RoleTemplate("backend-developer", "Backend / Core Developer", "Implement core logic and data layers.", DEV, setOf("code")))
            .register(RoleTemplate("qa-engineer", "QA Engineer", "Write and run tests; report defects.", Grant(read = listOf("**"), write = listOf("**/test/**", "**/*Test*"), execute = true, approve = true), setOf("test")))
            .register(RoleTemplate("code-reviewer", "Code Reviewer", "Review patches for bugs, architecture violations and maintainability.", REVIEW, setOf("review")))
            .register(RoleTemplate("security-auditor", "Security Auditor", "Review patches for vulnerabilities and unsafe handling of secrets.", REVIEW, setOf("review", "security")))
            .register(RoleTemplate("documentation-engineer", "Documentation Engineer", "Write and update documentation.", Grant(read = listOf("**"), write = listOf("**/*.md", "docs/**"))))
            .register(RoleTemplate("performance-engineer", "Performance Engineer", "Find and fix measured performance problems.", DEV, setOf("code", "perf")))
            .register(RoleTemplate("build-release-engineer", "Build and Release Engineer", "Maintain build files and release preparation.", Grant(read = listOf("**"), write = listOf("*.gradle*", "gradle/**", ".github/**"), execute = true)))
    }
}
