# Acceptance Criteria

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define the acceptance criteria that close tasks and phases.

## Scope

All acceptance tests in the control plane.

## Requirements

| Acceptance | Statement |
|---|---|
| AC-001 | Independent tasks run in parallel; dependent tasks wait for their prerequisites |
| AC-002 | Tasks whose declared file claims overlap are serialized; disjoint tasks run together |
| AC-003 | Number of running agents never exceeds the configured cap under a stress sequence |
| AC-004 | Exhausting a token or cost budget stops new work and cancels running agents |
| AC-005 | Replaying the event log reproduces the exact scheduler state after a simulated crash |
| AC-006 | Traversal, absolute, NUL, bidi and symlink paths are rejected by RelPath and the real filesystem |
| AC-007 | Restoring a snapshot reproduces every file byte for byte and detects a corrupted blob |
| AC-008 | Writes in a fork never change the base workspace or another fork |
| AC-009 | Malformed, oversized, traversal and sensitive-path ChangeSets are rejected before any write |
| AC-010 | Non-overlapping edits from two agents merge automatically and correctly |
| AC-011 | Overlapping or adjacent edits produce a conflict and no file is modified |
| AC-012 | Binary, CRLF, delete-vs-edit and add-in-both cases behave exactly as the edge-case table states |
| AC-013 | Applying a ChangeSet whose base is stale never overwrites newer content |
| AC-014 | A failure injected mid-apply restores the pre-apply snapshot |
| AC-015 | A change outside the task write grant is denied or escalated, never applied silently |
| AC-016 | A task cannot reach Verified without a review by a different agent or the user |
| AC-017 | Approval messages from the manager or the author do not satisfy a mandatory gate |
| AC-018 | A scan of agents.db, event log, audit trail and prompts finds no credential material |
| AC-019 | Static scan of agents-core finds no java.io.File, Uri, DocumentFile or ContentResolver |
| AC-020 | A SAF-backed project is refused at team creation with a clear message |
| AC-021 | After a simulated process kill, interrupted runs resume or are marked interrupted without data loss |
| AC-022 | Cancelling a team cancels all agents and releases their workspaces |
| AC-023 | The same run succeeds with a fake provider and with two different real provider kinds |
| AC-024 | UI shows each agent's status, files touched, decisions, tokens and remaining work |
| AC-025 | User can compare two competing results side by side and choose one |
| AC-026 | Manager output is a plan that cannot start without explicit user approval |
| AC-027 | Anvex release audit scripts pass with the module integrated |
| AC-028 | A diff of the main source touches only the files listed in the integration contract |
| AC-029 | Context sent to an agent stays within its configured size and excludes unrelated files |
| AC-030 | A process kill at any step of an apply is repaired on restart: the project equals its pre-apply state and the task is redone |
| AC-031 | Unusable agent output never reaches review: it becomes a retryable failure and is never integrated |
| AC-032 | A new agent role is added by registering a profile without modifying scheduler or integrator code |

## Decisions

- Each criterion needs an executable test or a documented manual procedure with recorded result.

## Rules

1. Evidence is a log with command, tool versions and exit status.
2. A criterion that cannot be executed in the current environment is UNVERIFIED, not passed.

## Examples

AC-019 evidence: the guard test output plus a deliberate violation that makes it fail.

## Future Considerations

Convert manual criteria to automated tests where possible.
