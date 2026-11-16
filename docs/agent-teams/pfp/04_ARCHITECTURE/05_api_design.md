# API Design (Internal Ports)

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define the internal API: the ports that connect the pure core to the outside world (ARC-PORT-1). The module exposes no network API.

## Scope

Kotlin interfaces in agents-core. Adapters live in agents-runtime or the host (ARC-INT-1).

## Requirements

| Port | Responsibility | Implemented by |
|---|---|---|
| `AgentRunnerPort` | `run(request) → RunResult` (ChangeSet or failure, usage) for one agent on one task in one workspace | agents-runtime (ARC-AND-1) |
| `WorkspaceProvider` / `WorkspaceFileSystem` | workspaces and snapshots | agents-fs |
| `VersionControlPort` | status/diff/commit of the real project via GitLite | host adapter |
| `ShellPort` | approved command execution in a workspace | host adapter, wrapping `ActionSafetyPolicy` |
| `ApprovalPort` | ask the user and return a decision | host adapter |
| `UsagePort` | read provider price info, report usage | host adapter |
| `EventStorePort` | append and read events | agents-runtime (Room) |
| `SensitivePathPort` | prefixes that no agent may touch (`SensitivePaths`) | host adapter |
| `ClockPort`, `IdPort` | time and IDs | host adapter |
| `ContextPort` | retrieve relevant files and symbols for a task | host adapter over `CodebaseIndexer` and `SymbolIndexManager` (core:android) |
Facade for the UI: `TeamEngineFacade` with intents such as createTeam, addAgent, addTask, startRun, pause, resume, stop, applyChangeSet, rollback.
Every port method is synchronous or `suspend` according to its layer: core ports are plain functions executed by a host-provided executor so the core needs no coroutine library (ADR-009).

## Decisions

- Ports are small and mockable; each has a fake in `agents-core/testFixtures` used by all unit tests.

## Rules

1. A port never returns raw `File`, `Uri` or absolute paths.
2. Port errors are typed results, not unchecked exceptions crossing layers.
3. Adding a port method requires updating fakes and the integration contract.

## Examples

`ApprovalPort.ask(Escalation("Agent B wants to modify build.gradle.kts"))` returns `Approved`, `Denied` or `TimedOut`; `TimedOut` is treated as Denied.

## Future Considerations

A remote-agent port can be added for cross-device teams without changing the engine.
