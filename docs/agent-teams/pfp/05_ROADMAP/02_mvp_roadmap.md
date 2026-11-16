# MVP Roadmap

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Show the shortest path to a usable Mode B team.

## Scope

The MVP path through milestones 1 to 4.

## Requirements

Path: scheduler and policy (pure) → workspaces and merge (pure plus filesystem) → persistence and runner (Android) → UI. After M4 a user can run and compare agents; M5 adds independent review and the propose-only manager; M6 integrates. The MVP exit gate is the pass of acceptance tests AC-001, AC-002, AC-003, AC-008, AC-009, AC-010, AC-011, AC-013, AC-014, AC-004, AC-021, AC-024, AC-025.

## Decisions

- The MVP intentionally contains no autonomous manager (ADR-025).

## Rules

1. The MVP exit gate requires recorded evidence for every listed test.
2. Open governance decisions use their recommended defaults until resolved (ADR-028, ADR-029).

## Examples

If device measurements in M3 show 3 concurrent agents exceed the memory budget, the default cap becomes 2 and the change is logged.

## Future Considerations

A beta with trusted users can run between M4 and M5.
