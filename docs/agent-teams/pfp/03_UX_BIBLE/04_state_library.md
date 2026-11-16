# State Library

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Enumerate UI states so every screen handles them.

## Scope

Task, agent, run and gate states as shown to the user.

## Requirements

| Domain | States |
|---|---|
| Task | Pending, Assigned, InProgress, Blocked, UnderReview, RevisionRequired, Completed, Failed, Cancelled |
| Agent | Idle, Running, Pausing, Paused, Stopping, Stopped, Errored |
| Run | Draft, Running, Paused, Interrupted, Completed, Cancelled, Failed |
| Gate | NotRequired, Required, Satisfied, Rejected |
| Screen | Loading, Empty, Content, Error |
Task states follow the specification; `Verified` is not a task state but a gate outcome (PRD-FR-12).

## Decisions

- Completed means integrated and gate-satisfied; an implementation that finished but awaits review is UnderReview.

## Rules

1. Every enum value has a localized label.
2. The state machine in agents-core is the only source of truth; UI never infers states.

## Examples

An agent that finishes writing moves its task InProgress → UnderReview, never directly to Completed.

## Future Considerations

Additional states for long-running workflows are added through the registry.
