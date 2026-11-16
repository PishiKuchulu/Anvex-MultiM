# Milestones

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

State entry and exit criteria for every milestone.

## Scope

Milestones RDM-M-0 to RDM-M-6.

## Requirements

| Milestone | Tasks | Exit acceptance |
|---|---|---|
| RDM-M-0 | TASK-001, TASK-002 | validator verdict APPROVED; baseline hash recorded |
| RDM-M-1 | TASK-003, TASK-004, TASK-005, TASK-006, TASK-007, TASK-008, TASK-009, TASK-010 | AC-001, AC-002, AC-003, AC-004, AC-005, AC-019, AC-032 |
| RDM-M-2 | TASK-011, TASK-012, TASK-013, TASK-014, TASK-015, TASK-016, TASK-017 | AC-006, AC-007, AC-008, AC-009, AC-010, AC-011, AC-012, AC-013, AC-014, AC-015 |
| RDM-M-3 | TASK-018, TASK-019, TASK-020, TASK-021 | AC-021, AC-022, AC-018, AC-023, AC-029, AC-020 |
| RDM-M-4 | TASK-025, TASK-026, TASK-027, TASK-028 | AC-024, AC-025 |
| RDM-M-5 | TASK-029, TASK-030 | AC-016, AC-017, AC-026 |
| RDM-M-6 | TASK-031, TASK-032 | AC-027, AC-028 |

## Decisions

- Entry criterion for every milestone: all exit criteria of the previous one are met with evidence.

## Rules

1. Milestone dependencies are linear; skipping requires a decision-log entry.
2. Exit evidence is attached to 11_LIVING_DOCUMENTATION/02_change_history.md.

## Examples

M1 closes when the scheduler, policy and guard tests pass under kotlinc and JUnit.

## Future Considerations

Add a beta milestone between M4 and M5 if real users are available.
