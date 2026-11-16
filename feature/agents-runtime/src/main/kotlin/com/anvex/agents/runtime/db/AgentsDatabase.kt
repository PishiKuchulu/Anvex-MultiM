package com.anvex.agents.runtime.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/* UNVERIFIED (never compiled). Separate database file agents.db with its own schema history (ADR-016): anvex.db (version 13) is untouched. */

@Entity(tableName = "event", indices = [Index(value = ["runId", "seq"], unique = true)])
data class EventEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: String, val seq: Long, val json: String, val prevHash: String, val hash: String)

@Entity(tableName = "apply_journal")
data class JournalEntity(@PrimaryKey val taskId: String, val runId: String, val rollbackSnapshot: String)

@Entity(tableName = "audit", indices = [Index("runId")])
data class AuditEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: String, val ts: Long, val kind: String, val taskId: String?, val agentId: String?, val detail: String)

@Entity(tableName = "team")
data class TeamEntity(@PrimaryKey val id: String, val projectId: String, val name: String, val maxConcurrent: Int, val maxTokens: Long?, val maxCostUsd: Double?, val createdAt: Long)

@Entity(tableName = "agent", primaryKeys = ["teamId", "agentId"])
data class AgentEntity(val teamId: String, val agentId: String, val profileJson: String)

@Entity(tableName = "run", indices = [Index("teamId")])
data class RunEntity(@PrimaryKey val id: String, val teamId: String, val projectId: String, val state: String, val startedAt: Long, val finishedAt: Long?)

@Dao
interface EventDao {
    @Insert fun insert(e: EventEntity)
    @Query("SELECT * FROM event WHERE runId = :runId ORDER BY seq ASC") fun all(runId: String): List<EventEntity>
}

@Dao
interface JournalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(e: JournalEntity)
    @Query("DELETE FROM apply_journal WHERE taskId = :taskId AND runId = :runId") fun remove(runId: String, taskId: String)
    @Query("SELECT * FROM apply_journal WHERE runId = :runId ORDER BY rowid ASC") fun pending(runId: String): List<JournalEntity>
}

@Dao
interface AuditDao {
    @Insert fun insert(e: AuditEntity)
    @Query("SELECT * FROM audit WHERE runId = :runId ORDER BY id ASC") fun all(runId: String): List<AuditEntity>
}

@Dao
interface TeamDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putTeam(t: TeamEntity)
    @Query("SELECT * FROM team WHERE projectId = :projectId ORDER BY createdAt DESC") fun teams(projectId: String): List<TeamEntity>
    @Query("SELECT * FROM team WHERE id = :id") fun team(id: String): TeamEntity?
    @Query("DELETE FROM team WHERE id = :id") fun deleteTeam(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putAgent(a: AgentEntity)
    @Query("SELECT * FROM agent WHERE teamId = :teamId ORDER BY agentId") fun agents(teamId: String): List<AgentEntity>
    @Query("DELETE FROM agent WHERE teamId = :teamId AND agentId = :agentId") fun deleteAgent(teamId: String, agentId: String)
    @Query("DELETE FROM agent WHERE teamId = :teamId") fun deleteAgents(teamId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putRun(r: RunEntity)
    @Query("SELECT * FROM run WHERE teamId = :teamId ORDER BY startedAt DESC") fun runs(teamId: String): List<RunEntity>
    @Query("SELECT * FROM run WHERE state IN ('Running','Interrupted') ORDER BY startedAt DESC") fun openRuns(): List<RunEntity>
}

@Database(entities = [EventEntity::class, JournalEntity::class, AuditEntity::class, TeamEntity::class, AgentEntity::class, RunEntity::class], version = 1, exportSchema = true)
abstract class AgentsDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun journal(): JournalDao
    abstract fun audit(): AuditDao
    abstract fun teams(): TeamDao
}
