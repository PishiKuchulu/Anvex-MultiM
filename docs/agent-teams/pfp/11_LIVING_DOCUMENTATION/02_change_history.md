# Change History

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Keep a chronological record of package and implementation changes.

## Scope

Package versions and milestone completions.

## Requirements

| Date | Change | Evidence |
|---|---|---|
| 2026-11-05 | Package 1.0.0 created in hybrid mode from the product specification, the Anvex 4.15.2 scan and the owner's directives | PFP_VALIDATION_REPORT.md |
| 2026-11-12 | Package 1.1.0: implementation of milestones M1 to M5 (pure parts) and the Android layer; decisions ADR-030 to ADR-034 added (orchestrator in core, canonical event hashing, requeue and apply journal, canonical RelPath, fs test module) | evidence/ (agents-core.log, agents-fs.log, mutation-results.txt) |

## Decisions

- Milestone entries are added when exit gates are met, with links to evidence.

## Rules

1. Append only; corrections are new entries.
2. Include validator verdicts and test logs by reference.

## Examples

M1 closes: entry lists the seven acceptance tests and the log path.

## Future Considerations

Automate entries from CI.
