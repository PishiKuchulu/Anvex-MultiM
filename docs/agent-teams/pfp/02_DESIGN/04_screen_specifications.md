# Screen Specifications

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Specify each screen's content and actions.

## Scope

MVP screens only.

## Requirements

1. **Teams list:** teams for the current project; create, duplicate, delete.
2. **Team detail:** agent cards, shared budget, concurrency cap, start/stop run.
3. **Agent editor:** name, role text, provider, model, temperature, permissions (read/write globs, execute, approve), limits.
4. **Task board:** list of tasks with state, assignees, dependencies; add task with acceptance criteria and expected files.
5. **Run monitor:** live agent status, activity timeline, files touched, tokens and cost, notifications.
6. **Diff and compare:** per-agent ChangeSets, side-by-side compare, apply or reject.
7. **Conflicts:** conflict panel per file.
8. **Review and approval:** gate status, reviewer findings, approve/reject by the user.
9. **Usage:** per-agent and per-task tokens and cost (cost only when prices are configured).

## Decisions

- Screens 1–9 satisfy PRD-FR-10, PRD-FR-8, PRD-FR-9, PRD-FR-11 and PRD-FR-19.

## Rules

1. Every screen has loading, empty and error states.
2. A screen never shows secrets; provider references show only the display name.

## Examples

Run monitor on a phone: top budget bar, below it a vertical list of agent cards, bottom sheet with the event timeline.

## Future Considerations

A tablet two-pane layout places the task board beside the run monitor.
