# MVP Definition

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Fix exactly what the first usable release contains so that scope does not drift.

## Scope

The MVP is the result of milestones RDM-M-1 to RDM-M-4. Milestone RDM-M-5 completes the quality gates and is required before the module is called production-ready. Milestone RDM-M-6 integrates.

## Requirements

**MVP includes:** user-managed teams (Mode B), per-agent provider/model/temperature, parallel runs with a concurrency cap, isolated CAS-backed workspaces, ChangeSet validation, three-way merge with conflict reporting, rollback, budgets and usage display, persistence and recovery, Agent Teams UI in English and Persian.

**MVP excludes:** autonomous manager (ADR-025), profile import/export (ADR-026), SAF-backed projects (ADR-027), automatic approval of low-risk changes (ADR-029 default: reviewer always required), rename detection in merges, file mode and symlink changes.

MVP requirements: PRD-FR-2, PRD-FR-3, PRD-FR-5, PRD-FR-6, PRD-FR-7, PRD-FR-8, PRD-FR-9, PRD-FR-10, PRD-FR-11, PRD-FR-13, PRD-FR-15, PRD-FR-16, PRD-FR-17, PRD-FR-18, PRD-FR-19, PRD-FR-20, PRD-FR-21, PRD-FR-22, PRD-FR-23, PRD-FR-24.

## Decisions

- Scope reductions are recorded as scope decisions SD-001 to SD-005; nothing else is silently cut.

## Rules

1. A feature not listed under "MVP includes" needs a decision-log entry before work starts.
2. The MVP is releasable only when every MVP acceptance test passes with recorded evidence.

## Examples

A team created on a SAF-backed project fails closed with the message that this project type is not supported yet (AC-020).

## Future Considerations

Post-MVP order: reviewer automation, manager autonomy, SAF gateway, import/export.
