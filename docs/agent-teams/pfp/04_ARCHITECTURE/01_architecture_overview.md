# Architecture Overview

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define the module's structure, boundaries and the stable seams used for the final integration into Anvex (PRD-FR-47, PRD-FR-29).

## Scope

Whole module. Anvex internals are described only where the module touches them. Facts about Anvex 4.15.2 below were verified by reading its source.

## Requirements

**Verified facts about the host (Anvex 4.15.2):** `com.anvex.core.engine.ProjectFileSystem` is a root-relative interface (mkdirs, exists, isDirectory, writeFile, writeFileBytes, readFile, readFileBytes, appendFile, delete, list, move, copy, walk, walkWithSizes, fileSize) implemented by `RealProjectFileSystem` (atomic writes, canonical-path confinement, symlink-safe delete and walk) and `SafProjectFileSystem`. `core:engine` is a pure-JVM Gradle module. `GitLite` has branch, checkout, commit and diff but no merge, rebase or worktree. `DurableTaskEngine.drain()` runs tasks strictly one at a time. `AnvexDatabase` is at schema version 13.

**Modules**
```
:feature:agents-core     pure JVM, Kotlin stdlib only      domain, scheduler, policy, changeset, merge, integrator, ports
:feature:agents-fs       JVM; depends on agents-core and :core:engine   RealWorkspaceFileSystem, CAS, fork
:feature:agents-runtime  Android library                   Room agents.db, AgentRunner, execution host, adapters
:feature:agents-ui       Android library (Compose)         screens and view models
```
Dependency direction: ui → runtime → fs → core. `core` depends on nothing in Anvex (ADR-009).

**Components**

| ID | Component |
|---|---|
| ARC-CORE-1 | Domain model, task state machine and append-only event log |
| ARC-CORE-2 | Conflict-aware parallel scheduler (pure state machine) |
| ARC-CORE-3 | ChangeSet model and untrusted-output validator |
| ARC-CORE-4 | Integrator: three-way merge, conflict detection, atomic apply |
| ARC-CORE-5 | Review and verification gates (maker is never verifier) |
| ARC-CORE-6 | Budget, rate-limit and usage ledger |
| ARC-CORE-7 | Context retrieval and controlled distribution |
| ARC-CORE-8 | Agent profile registry and extension points |
| ARC-PORT-1 | Port set: runner, workspace provider, version control, shell, approval, usage, store, clock |
| ARC-FS-1 | WorkspaceFileSystem port and root-relative path model |
| ARC-FS-2 | Content-addressed snapshot store |
| ARC-FS-3 | Isolated workspace fork and base-snapshot lifecycle |
| ARC-FS-4 | SAF import/export gateway (deferred) |
| ARC-SEC-1 | Policy engine: ownership, permissions, fail-closed admission |
| ARC-SEC-2 | Credential reference model: no secrets in module state |
| ARC-SEC-3 | Append-only audit trail with evidence chain |
| ARC-AND-1 | AgentRunner adapter over ProviderClient, BoltStreamParser, ActionDispatcher |
| ARC-AND-2 | Separate Room database agents.db and event store |
| ARC-AND-3 | Execution host: coroutine runtime and foreground service |
| ARC-UI-1 | Agent Teams Compose UI |
| ARC-INT-1 | Integration contract and Anvex adapters |
| ARC-CORE-9 | Orchestrator: command executor with write-ahead events, output vetting and recovery |
| ARC-CORE-10 | Apply journal and crash recovery of the integration workspace |

Component-to-requirement traceability is in PFP_CONTROL_PLANE.json.

## Decisions

- ADR-009, ADR-020, ADR-021, ADR-002, ADR-016, ADR-023.
- The sequence for one task: Scheduler admits task → WorkspaceProvider forks → AgentRunner edits the fork → ChangeSet extracted → ChangeSetValidator → PolicyEngine → Gate (review) → Integrator applies to the integration workspace → event and audit recorded.

## Rules

1. Dependencies only point in the direction shown; a violation fails the architecture guard test (AC-019).
2. Components in agents-core never perform I/O; all I/O passes through ports (ARC-PORT-1).
3. The module is built and verified in a copy of Anvex 4.15.2 and integrated only at the contract points (ADR-023).

## Examples

A two-agent run: A and B each get a fork of snapshot S0. Both produce ChangeSets. A is applied; B's base is S0 but the integration workspace is now S1, so B goes through the three-way merge instead of a blind write.

## Future Considerations

Multiple managers, cross-team work and performance evaluation attach to the registry and the event log without changing the scheduler contract (PRD-FR-28).
