# Scalability Plan

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Explain how the module grows in agents, tasks and projects without redesign (PRD-FR-28).

## Scope

Growth within one device. Multi-device scaling is covered in future platforms.

## Requirements

- Agents: the cap is configuration; the scheduler is pure and handles hundreds of tasks (BUD-SCHED).
- Projects: each team references a project by ID; storage grows with CAS deduplication.
- Event log: projections are rebuildable, so compaction can be added.
- Providers: new providers appear through the host provider factory; no engine change (ADR-018).
- New agent roles: registered as profiles (AC-032).

## Decisions

- Scaling limits are device memory, storage and provider rate limits, tracked by budgets and the ledger.

## Rules

1. Do not add global state that prevents several runs per device.
2. Keep the core free of Android types so it can move to other runtimes.

## Examples

Two teams on two projects run at the same time with separate caps and separate budgets.

## Future Considerations

Backpressure tied to provider rate-limit headers.
