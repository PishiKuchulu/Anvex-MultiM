# Definition of Done

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Fix the definition of done for tasks, phases and the module.

## Scope

All work in this package.

## Requirements

**Task done:** code and tests merged; listed acceptance tests pass with evidence; architecture guard green; no warnings in agents-core; report written.
**Phase done:** all tasks done; independent review verdict accept; owner acknowledgment; change history updated.
**Module done:** phases 0–6 done; release audit passes; integration diff limited to contract points (AC-028, AC-027); device checklist executed; every UNVERIFIED item either verified or explicitly accepted by the owner.

## Decisions

- Definition of done is not negotiable per task.

## Rules

1. No partial credit: a task with a failing criterion is not done.
2. Done is recorded only after evidence exists.

## Examples

Phase 2 is not done while AC-012 is red, even if all other tests pass.

## Future Considerations

Add performance budget verification to the module's definition of done after M3.
