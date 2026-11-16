# Development Phases

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define the ordered phases of the build so readers can follow the roadmap in order (V4).

## Scope

Phases M0 to M6. Each phase has entry and exit gates in 03_milestones.md.

## Requirements

**M0**: Foundation: PFP package validated and Anvex 4.15.2 baseline frozen (RDM-M-0).
**M1**: Core: domain, events, scheduler, budgets, ports, policy (pure JVM) (RDM-M-1).
**M2**: Workspaces: path model, CAS snapshots, fork, ChangeSet validation, Integrator (RDM-M-2).
**M3**: Runtime: agents.db, AgentRunner adapter, execution host, adapters (RDM-M-3).
**M4**: UI: Agent Teams screens, diff comparison, approvals, i18n (RDM-M-4).
**M5**: Assurance: independent reviewer gates and propose-only manager (RDM-M-5).
**M6**: Integration: add the module to the main source and pass the release audit (RDM-M-6).

## Decisions

- Phases are strictly sequential for acceptance; UI mock-ups may start earlier but cannot be accepted earlier.
- Android-dependent phases (M3, M4) are labelled UNVERIFIED until built and run on a device or CI.

## Rules

1. A phase is closed only when all its acceptance tests have recorded evidence.
2. Work from a later phase may not begin if an earlier acceptance test is red.

## Examples

M2 cannot close while the merge edge-case test (AC-012) fails.

## Future Considerations

Post-M6 phases: reviewer automation, manager autonomy, SAF gateway, import/export.
