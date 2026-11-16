# User Stories

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define the acceptance contract in user terms; each story maps to requirements and to acceptance tests.

## Scope

Stories for the MVP (milestones RDM-M-1–RDM-M-5). Stories for deferred items appear at the end and are marked deferred.

## Requirements

- **US-1** As a builder I create a team of agents, each with its own provider, model and temperature (PRD-FR-3, PRD-FR-4). Accepted by AC-023.
- **US-2** I give one task to several agents and compare their results (PRD-FR-5, PRD-FR-8). Accepted by AC-025, AC-008.
- **US-3** I run agents in parallel and the system serializes only tasks that would touch the same files (PRD-FR-15). Accepted by AC-002.
- **US-4** I can pause, resume or stop any agent and see what it changed (PRD-FR-10). Accepted by AC-022, AC-024.
- **US-5** No change is applied without validation, and an agent can never approve itself (PRD-FR-46, PRD-FR-12). Accepted by AC-009, AC-016.
- **US-6** If two results overlap I see a conflict instead of a silent overwrite (PRD-FR-13). Accepted by AC-011, AC-013.
- **US-7** I set a budget and the run stops when it is reached (PRD-FR-20). Accepted by AC-004.
- **US-8 (deferred)** I import a project from a shared folder (PRD-FR-37, ADR-027).

## Decisions

Stories are acceptance-level; implementation tasks live in 19_IMPLEMENTATION_BLUEPRINT/05_task_breakdown.md.

## Rules

1. A story is done only when its acceptance test passes with recorded output.
2. A story may not be split to hide an unmet acceptance test.

## Examples

US-6: Agent A and Agent B both edit `Main.kt` lines 40–45. Applying A succeeds; applying B reports a conflict listing both hunks and leaves the file as A made it.

## Future Considerations

Stories for import/export and autonomous planning will be added when ADR-026 and ADR-025 leave the deferred state.
