package com.anvex.agents.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anvex.agents.core.domain.AgentId
import com.anvex.agents.core.domain.AgentLimits
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.BlockReason
import com.anvex.agents.core.domain.GateState
import com.anvex.agents.core.domain.Grant
import com.anvex.agents.core.domain.RunState
import com.anvex.agents.core.domain.TaskId
import com.anvex.agents.core.domain.TaskSpec
import com.anvex.agents.core.domain.TaskState
import com.anvex.agents.core.events.UserAction
import com.anvex.agents.core.scheduler.SchedulerState
import com.anvex.agents.core.scheduler.TaskNode
import com.anvex.agents.core.scheduler.UserNeed
import com.anvex.agents.runtime.AgentsGraph
import com.anvex.agents.runtime.HostProvider
import com.anvex.agents.runtime.db.TeamEntity
import com.anvex.agents.runtime.host.RunHandle
import java.util.UUID

/**
 * UNVERIFIED (never compiled). The module's single entry point: the host adds ONE navigation route that calls this composable
 * (ADR-023). All user-visible text comes from strings_agents.xml (en, fa); status is shown by text, never by color alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTeamsRoute(graph: AgentsGraph, projectId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val vm: AgentTeamsViewModel = viewModel(factory = AgentTeamsViewModel.Factory(ctx, graph, projectId))
    val screen by vm.screen.collectAsState(); val message by vm.message.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.refreshTeams() }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.clearMessage() } }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agents_title)) },
                navigationIcon = { IconButton(onClick = { if (screen is Screen.Teams) onClose() else vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.agents_back)) } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp)) {
            when (val s = screen) {
                is Screen.Teams -> TeamsScreen(vm)
                is Screen.Team -> TeamScreen(vm)
                is Screen.Run -> RunScreen(vm)
                is Screen.Diff -> DiffScreen(vm, s)
            }
        }
    }
}

@Composable private fun TeamsScreen(vm: AgentTeamsViewModel) {
    val teams by vm.teams.collectAsState(); var creating by rememberSaveable { mutableStateOf(false) }; var name by rememberSaveable { mutableStateOf("") }
    if (teams.isEmpty()) Text(stringResource(R.string.agents_no_teams), style = MaterialTheme.typography.bodyMedium)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
        items(teams, key = { it.id }) { t -> Card(onClick = { vm.openTeam(t.id) }, modifier = Modifier.fillMaxWidth()) { Text(t.name, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) } }
    }
    FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, stringResource(R.string.agents_new_team)) }
    if (creating) AlertDialog(
        onDismissRequest = { creating = false }, title = { Text(stringResource(R.string.agents_new_team)) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.agents_team_name)) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { vm.createTeam(name); name = ""; creating = false }) { Text(stringResource(R.string.agents_save)) } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.agents_cancel)) } },
    )
}

@Composable private fun TeamScreen(vm: AgentTeamsViewModel) {
    val d by vm.detail.collectAsState(); val detail = d ?: return
    var editing by remember { mutableStateOf<AgentProfile?>(null) }; var newAgent by rememberSaveable { mutableStateOf(false) }; var newTask by rememberSaveable { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
        item { Text(detail.team.name, style = MaterialTheme.typography.titleLarge) }
        item {
            var conc by rememberSaveable(detail.team.id) { mutableStateOf(detail.team.maxConcurrent.toString()) }
            var tok by rememberSaveable(detail.team.id) { mutableStateOf((detail.team.maxTokens ?: 0L).toString()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(conc, { conc = it.filter(Char::isDigit); conc.toIntOrNull()?.let { n -> vm.saveTeamSettings(detail.team.copy(maxConcurrent = n.coerceIn(1, 8))) } }, label = { Text(stringResource(R.string.agents_max_concurrent)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(tok, { tok = it.filter(Char::isDigit); vm.saveTeamSettings(detail.team.copy(maxTokens = tok.toLongOrNull()?.takeIf { v -> v > 0 })) }, label = { Text(stringResource(R.string.agents_token_budget)) }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
        items(detail.agents, key = { it.id.value }) { a ->
            Card(onClick = { editing = a }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(a.name, style = MaterialTheme.typography.titleMedium); Text("${a.providerRef} / ${a.model}", style = MaterialTheme.typography.bodySmall)
                    if (a.grant.approve) Text(stringResource(R.string.agents_can_approve), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item { OutlinedButton(onClick = { newAgent = true }) { Text(stringResource(R.string.agents_add_agent)) } }
        items(detail.tasks, key = { it.id.value }) { t ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(t.title, style = MaterialTheme.typography.titleSmall); Text(t.assignees.joinToString { it.value }, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { vm.removeTask(detail.team.id, t.id.value) }) { Text(stringResource(R.string.agents_delete)) }
                }
            }
        }
        item { OutlinedButton(onClick = { newTask = true }, enabled = detail.agents.isNotEmpty()) { Text(stringResource(R.string.agents_add_task)) } }
        item { Button(onClick = { vm.startRun() }, enabled = detail.agents.isNotEmpty() && detail.tasks.isNotEmpty()) { Text(stringResource(R.string.agents_start_run)) } }
    }
    if (newAgent || editing != null) AgentDialog(editing, vm.providers, onDismiss = { newAgent = false; editing = null },
        onSave = { vm.saveAgent(detail.team.id, it); newAgent = false; editing = null }, onDelete = editing?.let { e -> { vm.deleteAgent(detail.team.id, e.id.value); editing = null } })
    if (newTask) TaskDialog(detail.agents, onDismiss = { newTask = false }, onSave = { vm.addTask(detail.team.id, it); newTask = false })
}

@Composable private fun AgentDialog(existing: AgentProfile?, providers: List<HostProvider>, onDismiss: () -> Unit, onSave: (AgentProfile) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(existing?.name ?: "") }; var role by remember { mutableStateOf(existing?.role ?: "") }
    var provider by remember { mutableStateOf(existing?.providerRef ?: providers.firstOrNull()?.ref ?: "") }; var model by remember { mutableStateOf(existing?.model ?: providers.firstOrNull()?.models?.firstOrNull() ?: "") }
    var temp by remember { mutableStateOf(existing?.temperature?.toString() ?: "") }; var writes by remember { mutableStateOf(existing?.grant?.write?.joinToString(", ") ?: "**") }
    var exec by remember { mutableStateOf(existing?.grant?.execute ?: false) }; var approve by remember { mutableStateOf(existing?.grant?.approve ?: false) }
    var error by remember { mutableStateOf<String?>(null) }; var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.agents_add_agent)) },
        text = {
            Column(Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.agents_agent_name)) }, singleLine = true)
                OutlinedTextField(role, { role = it }, label = { Text(stringResource(R.string.agents_agent_role)) }, minLines = 3)
                Text(stringResource(R.string.agents_provider), style = MaterialTheme.typography.labelMedium)
                Column { TextButton(onClick = { menu = true }) { Text(providers.firstOrNull { it.ref == provider }?.displayName ?: provider.ifEmpty { "-" }) }
                    DropdownMenu(menu, { menu = false }) { providers.forEach { p -> DropdownMenuItem(text = { Text(p.displayName) }, onClick = { provider = p.ref; model = p.models.firstOrNull() ?: model; menu = false }) } } }
                OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.agents_model)) }, singleLine = true)
                OutlinedTextField(temp, { temp = it }, label = { Text(stringResource(R.string.agents_temperature)) }, singleLine = true)
                OutlinedTextField(writes, { writes = it }, label = { Text(stringResource(R.string.agents_write_globs)) }, singleLine = true)
                Row { Switch(exec, { exec = it }); Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.agents_can_execute)) }
                Row { Switch(approve, { approve = it }); Spacer(Modifier.padding(4.dp)); Text(stringResource(R.string.agents_can_approve)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = {
            try { onSave(AgentProfile(existing?.id ?: AgentId(UUID.randomUUID().toString().take(8)), name.trim(), role.trim().ifEmpty { "Work on the task carefully." }, provider, model.trim(), temp.toDoubleOrNull(),
                emptyMap(), Grant(write = writes.split(',').map { it.trim() }.filter { it.isNotEmpty() }, execute = exec, approve = approve), existing?.limits ?: AgentLimits(), existing?.capabilities ?: emptySet())) }
            catch (e: IllegalArgumentException) { error = e.message }   // the core validates everything (globs, credentials, ranges)
        }) { Text(stringResource(R.string.agents_save)) } },
        dismissButton = { Row { onDelete?.let { TextButton(onClick = it) { Text(stringResource(R.string.agents_delete)) } }; TextButton(onClick = onDismiss) { Text(stringResource(R.string.agents_cancel)) } } },
    )
}

@Composable private fun TaskDialog(agents: List<AgentProfile>, onDismiss: () -> Unit, onSave: (TaskSpec) -> Unit) {
    var title by remember { mutableStateOf("") }; var acc by remember { mutableStateOf("") }; var claims by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(setOf<String>()) }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.agents_add_task)) },
        text = {
            Column(Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.agents_task_title)) }, singleLine = true)
                OutlinedTextField(acc, { acc = it }, label = { Text(stringResource(R.string.agents_task_acceptance)) }, minLines = 2)
                OutlinedTextField(claims, { claims = it }, label = { Text(stringResource(R.string.agents_task_claims)) }, singleLine = true)
                Text(stringResource(R.string.agents_task_assignees), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    agents.forEach { a -> FilterChip(selected = a.id.value in chosen, onClick = { chosen = if (a.id.value in chosen) chosen - a.id.value else chosen + a.id.value }, label = { Text(a.name) }) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = {
            try { onSave(TaskSpec(TaskId("t" + UUID.randomUUID().toString().take(6)), title.trim(), acc.trim().ifEmpty { title.trim() }, emptySet(), claims.split(',').map { it.trim() }.filter { it.isNotEmpty() }, chosen.map { AgentId(it) })) }
            catch (e: IllegalArgumentException) { error = e.message }
        }) { Text(stringResource(R.string.agents_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.agents_cancel)) } },
    )
}

@Composable private fun stateLabel(s: TaskState) = stringResource(when (s) {
    TaskState.Pending -> R.string.agents_state_pending; TaskState.Assigned -> R.string.agents_state_assigned; TaskState.InProgress -> R.string.agents_state_in_progress
    TaskState.Blocked -> R.string.agents_state_blocked; TaskState.UnderReview -> R.string.agents_state_under_review; TaskState.RevisionRequired -> R.string.agents_state_revision
    TaskState.Completed -> R.string.agents_state_completed; TaskState.Failed -> R.string.agents_state_failed; TaskState.Cancelled -> R.string.agents_state_cancelled
})

@Composable private fun RunScreen(vm: AgentTeamsViewModel) {
    val h: RunHandle = vm.run.collectAsState().value ?: return
    val st: SchedulerState = h.state.collectAsState().value ?: return
    val totals = st.budget.ledger.total; val cap = st.budget.limits.maxTokens
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.agents_tokens_used, totals.tokens.toString()), style = MaterialTheme.typography.bodyMedium)
        if (totals.costUsd > 0.0) Text(stringResource(R.string.agents_cost_used, "%.4f".format(totals.costUsd)), style = MaterialTheme.typography.bodySmall)
        if (cap != null) LinearProgressIndicator(progress = { (totals.tokens.toFloat() / cap).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
        items(st.tasks.values.sortedBy { it.seq }, key = { it.spec.id.value }) { n -> TaskCard(vm, h, n) }
    }
}

@Composable private fun TaskCard(vm: AgentTeamsViewModel, h: RunHandle, n: TaskNode) {
    val id = n.spec.id.value
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { Text(n.spec.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); AssistChip(onClick = {}, label = { Text(stateLabel(n.state)) }) }
            for ((agent, rs) in n.runs) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${agent.value}: $rs", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (rs == RunState.Running) TextButton(onClick = { vm.pause(agent.value) }) { Text(stringResource(R.string.agents_pause)) }
                if (rs == RunState.Paused) TextButton(onClick = { vm.resume(agent.value) }) { Text(stringResource(R.string.agents_resume)) }
                if (rs.isLive) TextButton(onClick = { vm.stop(agent.value) }) { Text(stringResource(R.string.agents_stop)) }
            }
            if (n.awaitingChoice) { Text(stringResource(R.string.agents_choose_result)); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { n.runs.filterValues { it == RunState.Finished }.keys.forEach { a ->
                OutlinedButton(onClick = { vm.openDiff(h.runId, id, a.value) }) { Text(a.value) }; Button(onClick = { vm.choose(id, a.value) }) { Text(stringResource(R.string.agents_approve)) } } } }
            n.author?.let { a -> if (n.state == TaskState.UnderReview || n.state == TaskState.Blocked) OutlinedButton(onClick = { vm.openDiff(h.runId, id, a.value) }) { Text(stringResource(R.string.agents_show_changes)) } }
            if (n.state == TaskState.UnderReview && n.gate == GateState.Required && !n.awaitingChoice) {
                Text(stringResource(R.string.agents_gate_needs_review), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Button(onClick = { vm.userVerdict(id, true) }) { Text(stringResource(R.string.agents_approve)) }; OutlinedButton(onClick = { vm.userVerdict(id, false) }) { Text(stringResource(R.string.agents_reject)) } }
            }
            if (n.state == TaskState.Blocked) BlockedActions(vm, id, n)
            if (n.state == TaskState.Failed) { n.failure?.let { Text(it, style = MaterialTheme.typography.bodySmall) }; OutlinedButton(onClick = { vm.retryTask(id) }) { Text(stringResource(R.string.agents_retry)) } }
            if (n.state == TaskState.Completed) OutlinedButton(onClick = { vm.undo(id) }) { Text(stringResource(R.string.agents_undo)) }
            if (!n.state.isTerminal) TextButton(onClick = { vm.cancelTask(id) }) { Text(stringResource(R.string.agents_cancel)) }
        }
    }
}

@Composable private fun BlockedActions(vm: AgentTeamsViewModel, id: String, n: TaskNode) {
    val text = when {
        n.block == BlockReason.BudgetExhausted -> R.string.agents_need_budget
        n.userNeed == UserNeed.ResolveConflict -> R.string.agents_need_conflict
        n.userNeed == UserNeed.ApprovalForApply -> R.string.agents_need_approval
        n.userNeed == UserNeed.StoppedByUser -> R.string.agents_need_stopped
        else -> null
    }
    text?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
    if (n.block == BlockReason.AwaitingUser) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (n.userNeed == UserNeed.ApprovalForApply) Button(onClick = { vm.decide(id, UserAction.Approve) }) { Text(stringResource(R.string.agents_approve)) }
        OutlinedButton(onClick = { vm.decide(id, UserAction.Rerun) }) { Text(stringResource(R.string.agents_rerun)) }
        OutlinedButton(onClick = { vm.decide(id, UserAction.Reject) }) { Text(stringResource(R.string.agents_reject)) }
    }
}

@Composable private fun DiffScreen(vm: AgentTeamsViewModel, s: Screen.Diff) {
    val files = remember(s) { vm.diffs(s.task, s.agent) }
    if (files.isEmpty()) { Text(stringResource(R.string.agents_no_changes)); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(files, key = { it.first }) { (path, diff) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text(path, style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(4.dp))
                    // diffs are code: always left-to-right monospace, even in an RTL layout
                    SelectionContainer { Text(diff, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, textDirection = TextDirection.Ltr), modifier = Modifier.horizontalScroll(rememberScrollState())) }
                }
            }
        }
    }
}
