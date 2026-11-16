package com.anvex.agents.runtime

import android.content.Context
import androidx.room.Room
import com.anvex.agents.core.domain.AgentProfile
import com.anvex.agents.core.domain.ProfileCodec
import com.anvex.agents.runtime.db.AgentEntity
import com.anvex.agents.runtime.db.AgentsDatabase
import com.anvex.agents.runtime.db.TeamDao
import com.anvex.agents.runtime.db.TeamEntity
import com.anvex.agents.runtime.host.TeamRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** UNVERIFIED (never compiled). Teams and agent profiles; profiles are stored as strictly validated JSON (ProfileCodec). Blocking: call on IO. */
class TeamRepository(private val dao: TeamDao) {
    fun teams(projectId: String): List<TeamEntity> = dao.teams(projectId)
    fun team(id: String): TeamEntity? = dao.team(id)
    fun saveTeam(t: TeamEntity) = dao.putTeam(t)
    fun deleteTeam(id: String) { dao.deleteAgents(id); dao.deleteTeam(id) }
    fun agents(teamId: String): List<AgentProfile> = dao.agents(teamId).map { ProfileCodec.decode(it.profileJson) }
    fun saveAgent(teamId: String, p: AgentProfile) = dao.putAgent(AgentEntity(teamId, p.id.value, ProfileCodec.encode(p)))
    fun deleteAgent(teamId: String, agentId: String) = dao.deleteAgent(teamId, agentId)
}

/** Manual dependency graph of the module (ADR-017). The host's AppGraph creates one instance with its AgentsHost implementation. */
class AgentsGraph(appContext: Context, val host: AgentsHost) {
    val db: AgentsDatabase by lazy { Room.databaseBuilder(appContext.applicationContext, AgentsDatabase::class.java, "agents.db").build() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository: TeamRepository by lazy { TeamRepository(db.teams()) }
    val runtime: TeamRuntime by lazy { TeamRuntime(host, db, scope) }
}
