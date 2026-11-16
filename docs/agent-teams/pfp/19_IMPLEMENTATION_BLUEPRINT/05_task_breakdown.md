# Task Breakdown

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

List every implementation task with its phase, deliverable and verifying acceptance tests.

## Scope

All tasks of phases 0 to 6.

## Requirements

| Task | Phase | Deliverable | Verified by |
|---|---|---|---|
| TASK-001 Validate this PFP package and archive the validator report | M0 | validator log | AC-028 |
| TASK-002 Pin Anvex 4.15.2 as the compile baseline and record the source inventory hash | M0 | inventory hash entry | AC-027, AC-028 |
| TASK-003 Create Gradle modules agents-core and agents-fs aligned with the Anvex version catalog | M1 | Gradle modules, empty compile | AC-027, AC-028, AC-032 |
| TASK-004 Implement domain model and task state machine | M1 | Task, Agent, Team, Run, state machine | AC-001, AC-023, AC-032 |
| TASK-005 Implement append-only event log and deterministic replay | M1 | EventLog, Replay | AC-005, AC-021, AC-030 |
| TASK-006 Implement conflict-aware parallel scheduler with concurrency cap | M1 | Scheduler, claims, admission | AC-001, AC-002, AC-003, AC-008, AC-023, AC-025 |
| TASK-007 Implement budget, retry and usage ledger | M1 | Ledger, RetryPolicy | AC-004 |
| TASK-008 Define port interfaces with fake implementations | M1 | all port interfaces plus fakes | AC-001, AC-023, AC-032 |
| TASK-009 Implement policy engine with DENY>ESCALATE>DEFER>ALLOW precedence | M1 | PolicyEngine, Decision | AC-009, AC-015, AC-016, AC-017, AC-023, AC-031 |
| TASK-010 Add architecture guard test forbidding File, Uri, DocumentFile and ContentResolver in agents-core | M1 | ArchitectureGuardTest | AC-006, AC-019, AC-020 |
| TASK-011 Implement RelPath with parity tests against Anvex PathSanitizer | M2 | RelPath, parity test | AC-006, AC-019 |
| TASK-012 Implement RealWorkspaceFileSystem over ProjectFileSystem | M2 | RealWorkspaceFileSystem | AC-006, AC-008, AC-019, AC-020 |
| TASK-013 Implement CAS snapshot store, restore and verified reads | M2 | CasStore, Manifest, SnapshotId | AC-006, AC-007, AC-008, AC-014, AC-030 |
| TASK-014 Implement workspace fork and dispose lifecycle | M2 | Fork, dispose | AC-006, AC-008, AC-025 |
| TASK-015 Implement ChangeSet extraction and untrusted-output validator | M2 | ChangeSet, ChangeSetValidator | AC-009, AC-031 |
| TASK-016 Implement line-based three-way merge with the edge-case table | M2 | ThreeWayMerge, ConflictKind | AC-010, AC-011, AC-012, AC-013 |
| TASK-017 Implement Integrator with atomic apply and rollback snapshot | M2 | Integrator, ApplyResult | AC-007, AC-010, AC-011, AC-013, AC-014, AC-015, AC-030 |
| TASK-018 Implement agents.db entities, DAOs, migrations and schema snapshots | M3 | AgentsDatabase, DAOs, schema snapshots | AC-004, AC-005, AC-021, AC-028, AC-030, AC-032 |
| TASK-019 Implement AgentRunner adapter and ProviderPort | M3 | AgentRunner, ProviderPort, ContextPort adapter | AC-018, AC-023, AC-029 |
| TASK-020 Implement execution host, foreground service and cancellation propagation | M3 | ExecutionHost, foreground service | AC-001, AC-003, AC-004, AC-005, AC-021, AC-022, AC-024, AC-028, AC-030 |
| TASK-021 Implement Anvex adapters listed in the integration contract | M3 | adapters per integration contract | AC-018, AC-020 |
| TASK-022 Implement the Orchestrator, RunLauncher port and write-ahead persistence in agents-core | M3 | Orchestrator, RunLauncher, SnapshotCatalog | AC-001, AC-005, AC-009, AC-021, AC-023, AC-030, AC-031 |
| TASK-023 Implement the canonical event codec and the credential-guarded event store | M3 | EventCodec, GuardedEventStore | AC-005, AC-009, AC-021, AC-030, AC-031 |
| TASK-024 Implement the apply journal, crash recovery and the Requeue event | M3 | ApplyJournal, ApplyRecovery, Requeue event | AC-005, AC-007, AC-014, AC-021, AC-030 |
| TASK-025 Implement team, agent, task and run-monitor screens | M4 | Teams, Agent editor, Task board, Run monitor | AC-022, AC-024 |
| TASK-026 Implement diff comparison and conflict screens | M4 | DiffView, CompareSheet, ConflictPanel | AC-008, AC-025 |
| TASK-027 Implement approval, budget and notification surfaces | M4 | GateBanner, BudgetBar, notifications | AC-016, AC-024 |
| TASK-028 Provide English and Persian strings with RTL layout | M4 | strings_agents.xml en/fa, RTL | AC-027, AC-028 |
| TASK-029 Implement independent reviewer task type and gate enforcement | M5 | ReviewerTask, GateEnforcer | AC-016, AC-017, AC-023, AC-024 |
| TASK-030 Implement propose-only manager producing a validated task graph | M5 | ManagerPlanner, PlanValidator | AC-026 |
| TASK-031 Add the module to the main source following the integration contract | M6 | contract-point edits | AC-027, AC-028 |
| TASK-032 Run Anvex release audit and manual device checklist | M6 | audit logs, device checklist | AC-027, AC-028 |

## Decisions

- Tasks map to the control-plane tasks; acceptance IDs map to control-plane acceptance tests.

## Rules

1. A task is done when its listed acceptance tests pass with evidence.
2. Tasks are not split across phases.

## Examples

TASK-016 is done when AC-010, AC-011 and AC-012 pass.

## Future Considerations

New tasks for deferred decisions are added when they are activated.
