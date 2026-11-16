# Authorization

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define who may read, modify, execute and approve inside the engine (PRD-FR-11, PRD-FR-14).

## Scope

Agent grants, task claims, gates and user approvals.

## Requirements

**Grants** per agent: read globs, write globs, execute allowed (yes/no), approve allowed (yes/no); defaults: read the project, write nothing, execute no, approve no.
**Claims** per task: expected files; used for scheduling and for scope checks.
**Decisions** by PolicyEngine for every ChangeSet entry and command: ALLOW, DENY, ESCALATE, DEFER; precedence DENY > ESCALATE > DEFER > ALLOW.
- A write outside grant ∩ (claim ∪ user-approved extras) is ESCALATE, or DENY when the path is sensitive.
- Execution requests go through `ShellPort` and the host's `ActionSafetyPolicy`; the module can only tighten, never loosen.
- **Gates:** mandatory review means a verdict from an agent other than the author, or from the user. A message from any agent, including the manager, cannot satisfy a gate (AC-017, AC-016).
- Final release approval is always the user (PRD-FR-9).

## Decisions

- Enforcement point is the core (ADR-007); ports do not authorize.

## Rules

1. Grants are evaluated at apply time, not only at assignment time.
2. Changing a grant during a run takes effect for the next decision.

## Examples

A task claims `app/src/**`; the agent also edits `build.gradle.kts` → ESCALATE; the user approves once for that ChangeSet only.

## Future Considerations

Role templates with preconfigured grants (reviewer: read-only) can be offered.
