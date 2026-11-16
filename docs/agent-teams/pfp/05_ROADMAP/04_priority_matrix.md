# Priority Matrix

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Rank work by risk reduction and value so that scarce effort goes to the riskiest parts first.

## Scope

Components of the module.

## Requirements

| Component | Value | Risk if wrong | Priority |
|---|---|---|---|
| Integrator and merge | High | Critical (data loss) | P1 |
| ChangeSet validation, policy | High | Critical (security) | P1 |
| Scheduler | High | High | P1 |
| CAS snapshots, fork | High | High | P2 |
| Persistence and recovery | High | High | P2 |
| Budgets | Medium | High (cost) | P2 |
| UI comparison | High | Medium | P3 |
| Reviewer gates | High | High | P3 |
| Manager (propose-only) | Medium | Medium | P4 |

## Decisions

- P1 items get the deepest tests, including property and chaos tests.

## Rules

1. Re-evaluate after each milestone.
2. Risk entries in the control plane stay aligned with this matrix.

## Examples

An UI polish task never preempts a failing P1 test.

## Future Considerations

Add measured cost and latency to the matrix after M3.
