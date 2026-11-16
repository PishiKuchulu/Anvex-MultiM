# Performance Strategy

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Keep multi-agent runs responsive and affordable on a phone.

## Scope

Engine and runtime performance. UI rendering follows normal Compose guidance.

## Requirements

Budgets (control plane governance.budgets):
- BUD-SCHED: scheduler transition at most 5 ms for 100 tasks (JVM).
- BUD-MERGE: three-way merge of a 5,000-line file at most 500 ms (JVM).
- BUD-STORE: fork storage at most project size plus 10 percent; blobs deduplicated.
- BUD-MEM: agents-core heap at most 64 MB with 3 agents and 100 tasks.
- BUD-TOKEN and BUD-COST: default per-run hard caps, user-configurable.
Strategies: ignore rules exclude build outputs and VCS data from snapshots; hash computation streams files; diffs are computed per file lazily; event writes are batched in a transaction per scheduler step; at most the configured number of agents run (ADR-028).

## Decisions

- Measurements are taken in milestones RDM-M-1–RDM-M-3 and stored as evidence; defaults for concurrency are decided with device data (ADR-028).

## Rules

1. No optimization without a measurement that shows the problem.
2. A budget miss is reported with numbers.

## Examples

Snapshotting a 40 MB project with 800 files reads each file once and writes only blobs not already in the CAS.

## Future Considerations

Incremental snapshots using file modification times can be added later; correctness still relies on digests.
