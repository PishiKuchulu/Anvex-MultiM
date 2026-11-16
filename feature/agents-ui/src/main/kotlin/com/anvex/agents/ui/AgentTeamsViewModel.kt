package com.anvex.agents.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anvex.agents.core.budget.BudgetLimits
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskSpec
import com.anvex.agents.core.events.Reviewer
import com.anvex.agents.core.events.SchedulerEvent
import com.anvex.agents.core.events.UserAction
import com.anvex.agents.core.events.VerdictChannel
import com.anvex.agents.core.scheduler.SchedulerConfig
import com.anvex.agents.runtime.AgentsGraph
import com.anvex.agents.runtime.db.TeamEntity
import com.anvex.agents.runtime.host.RunHandle
import com.anvex.agents.runtime.host.UnsupportedProjectException
import com.anvex.agents.runtime.service.AgentTeamsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface Screen {
    data object Teams : Screen
    data class Team(val teamId: String) : Screen
    data class Run(val runId: String) : Screen
    data class Diff(val runId: String, val task: String, val agent: String) : Screen
}

data class TeamDetail(val team: TeamEntity, val agents: List<AgentProfile>, val tasks: List<TaskSpec>)

/**
 * UNVERIFIED (never compiled). Holds UI state only; every rule (gates, conflicts, budgets) lives in the tested core.
 * Task drafts are kept in memory (not persisted) in this version.
 */
class AgentTeamsViewModel(private val appContext: Context, private val graph: AgentsGraph, private val projectId: String) : ViewModel() {
    val screen = MutableStateFlow<Screen>(Screen.Teams)
    val teams = MutableStateFlow<List<TeamEntity>>(emptyList())
    val detail = MutableStateFlow<TeamDetail?>(null)
    val run = MutableStateFlow<RunHandle?>(null)
    val message = MutableStateFlow<String?>(null)
    val providers = graph.host.listProviders()
    private val drafts = HashMap<String, MutableList<TaskSpec>>()

    fun refreshTeams() { viewModelScope.launch(Dispatchers.IO) { teams.value = graph.repository.teams(projectId) } }

    fun createTeam(name: String) = io {
        val t = TeamEntity(UUID.randomUUID().toString(), projectId, name.trim().ifEmpty { "Team" }, maxConcurrent = 3, maxTokens = null, maxCostUsd = null, createdAt = System.currentTimeMillis())
        graph.repository.saveTeam(t); teams.value = graph.repository.teams(projectId)
    }
    fun deleteTeam(id: String) = io { graph.repository.deleteTeam(id); teams.value = graph.repository.teams(projectId); screen.value = Screen.Teams }

    fun openTeam(id: String) = io { loadDetail(id); screen.value = Screen.Team(id) }
    private fun loadDetail(id: String) { val t = graph.repository.team(id) ?: return; detail.value = TeamDetail(t, graph.repository.agents(id), drafts[id].orEmpty()) }

    fun saveAgent(teamId: String, p: AgentProfile) = io { graph.repository.saveAgent(teamId, p); loadDetail(teamId) }
    fun deleteAgent(teamId: String, agentId: String) = io { graph.repository.deleteAgent(teamId, agentId); loadDetail(teamId) }
    fun saveTeamSettings(t: TeamEntity) = io { graph.repository.saveTeam(t); loadDetail(t.id) }
    fun addTask(teamId: String, spec: TaskSpec) = io { drafts.getOrPut(teamId) { ArrayList() }.add(spec); loadDetail(teamId) }
    fun removeTask(teamId: String, id: String) = io { drafts[teamId]?.removeAll { it.id.value == id }; loadDetail(teamId) }

    fun startRun() = io {
        val d = detail.value ?: return@io
        if (d.agents.isEmpty() || d.tasks.isEmpty()) return@io
        val cfg = SchedulerConfig(maxConcurrent = d.team.maxConcurrent.coerceAtLeast(1), limits = BudgetLimits(d.team.maxTokens?.takeIf { it > 0 }, d.team.maxCostUsd?.takeIf { it > 0.0 }))
        try {
            val h = graph.runtime.start(UUID.randomUUID().toString(), d.team.id, projectId, cfg, d.agents, d.tasks)
            AgentTeamsService.start(appContext); run.value = h; screen.value = Screen.Run(h.runId)
        } catch (e: UnsupportedProjectException) { message.value = e.message } catch (e: Exception) { message.value = "${e::class.simpleName}: ${e.message}" }
    }

    // ---- user actions during a run: each is just an event; the core decides what is allowed
    private fun now() = System.currentTimeMillis()
    fun pause(agent: String) = send { SchedulerEvent.PauseAgent(now(), AgentId(agent)) }
    fun resume(agent: String) = send { SchedulerEvent.ResumeAgent(now(), AgentId(agent)) }
    fun stop(agent: String) = send { SchedulerEvent.StopAgent(now(), AgentId(agent)) }
    fun cancelTask(task: String) = send { SchedulerEvent.CancelTask(now(), TaskId(task)) }
    fun retryTask(task: String) = send { SchedulerEvent.RetryTask(now(), TaskId(task)) }
    fun decide(task: String, a: UserAction) = send { SchedulerEvent.UserDecision(now(), TaskId(task), a) }
    fun choose(task: String, agent: String) = send { SchedulerEvent.UserChoseResult(now(), TaskId(task), AgentId(agent)) }
    /** The USER's verdict: only this path (channel UserUi) can satisfy a gate on behalf of the user. */
    fun userVerdict(task: String, approve: Boolean) = send { SchedulerEvent.ReviewVerdict(now(), TaskId(task), Reviewer.User, approve, VerdictChannel.UserUi) }
    fun undo(task: String) = io { run.value?.orchestrator?.rollbackApplied(TaskId(task)) }

    fun diffs(task: String, agent: String): List<Pair<String, String>> = run.value?.diffs?.invoke(TaskId(task), AgentId(agent)).orEmpty()
    fun back() { screen.value = when (val s = screen.value) { is Screen.Diff -> Screen.Run(s.runId); is Screen.Run -> detail.value?.let { Screen.Team(it.team.id) } ?: Screen.Teams; is Screen.Team -> Screen.Teams; else -> Screen.Teams } }
    fun openDiff(runId: String, task: String, agent: String) { screen.value = Screen.Diff(runId, task, agent) }
    fun clearMessage() { message.value = null }

    private fun send(e: () -> SchedulerEvent) { val h = run.value ?: return; viewModelScope.launch(Dispatchers.IO) { h.submit(e()) } }
    private fun io(block: () -> Unit) { viewModelScope.launch(Dispatchers.IO) { try { block() } catch (e: Exception) { message.value = "${e::class.simpleName}: ${e.message}" } } }

    class Factory(private val ctx: Context, private val graph: AgentsGraph, private val projectId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AgentTeamsViewModel(ctx.applicationContext, graph, projectId) as T
    }
}
