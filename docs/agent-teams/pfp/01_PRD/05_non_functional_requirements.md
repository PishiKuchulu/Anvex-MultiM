# Non-Functional Requirements

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Set measurable quality targets so that performance, resource and reliability claims are testable.

## Scope

Targets apply to the module on a mid-range Android device (6 GB RAM, Android 12 or newer) and to the JVM test environment. Values are targets to be measured, not measured results.

## Requirements

- **PRD-NFR-1 Scheduling latency:** one scheduler transition for a graph of 100 tasks takes at most 5 ms on the JVM (BUD-SCHED).
- **PRD-NFR-2 Merge speed:** merging a 5,000-line text file takes at most 500 ms on the JVM (BUD-MERGE).
- **PRD-NFR-3 Storage overhead:** a fork of a project adds at most the project's own size plus 10 percent; repeated snapshots share identical blobs (BUD-STORE).
- **PRD-NFR-4 Memory:** agents-core with 3 agents and 100 tasks stays under 64 MB heap (BUD-MEM).
- **PRD-NFR-5 Durability:** after a process kill, no accepted ChangeSet is lost and no workspace is half-applied (AC-014, AC-021).
- **PRD-NFR-6 Cost safety:** default hard caps exist for tokens and cost (AC-004).
- **PRD-NFR-7 Localization:** all strings exist in English and Persian; layouts mirror correctly (TASK-028).
- **PRD-NFR-8 Accessibility:** every control has a content description and touch targets are at least 48 dp.
- **PRD-NFR-9 Observability:** every state transition and policy decision is an audit event (ARC-SEC-3).

## Decisions

- Targets are verified in milestones RDM-M-1–RDM-M-4 and recorded as evidence; a missed target must be reported, not relaxed silently.

## Rules

1. A budget in the control plane is only claimed as met with a recorded measurement.
2. NFR changes follow the updates process in 11_LIVING_DOCUMENTATION/03_updates_process.md.

## Examples

NFR-3: a 40 MB project forked for three agents costs about 120 MB plus CAS blobs shared with the base snapshot; the UI shows this before the run starts.

## Future Considerations

Real device measurements in milestone RDM-M-3 may adjust targets through a decision-log entry.
