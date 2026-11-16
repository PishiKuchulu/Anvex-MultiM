# Implementation Phases

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Translate the roadmap into implementation phases with entry and exit conditions for the coding agent.

## Scope

Phases correspond one to one with milestones RDM-M-0–RDM-M-6.

## Requirements

- **Phase 0** (RDM-M-0): Foundation: PFP package validated and Anvex 4.15.2 baseline frozen
- **Phase 1** (RDM-M-1): Core: domain, events, scheduler, budgets, ports, policy (pure JVM)
- **Phase 2** (RDM-M-2): Workspaces: path model, CAS snapshots, fork, ChangeSet validation, Integrator
- **Phase 3** (RDM-M-3): Runtime: agents.db, AgentRunner adapter, execution host, adapters
- **Phase 4** (RDM-M-4): UI: Agent Teams screens, diff comparison, approvals, i18n
- **Phase 5** (RDM-M-5): Assurance: independent reviewer gates and propose-only manager
- **Phase 6** (RDM-M-6): Integration: add the module to the main source and pass the release audit
Each phase starts after the previous phase's acceptance evidence is accepted by the independent reviewer and the owner.

## Decisions

- Phase order is fixed; UI mock-ups may precede Phase 4 but are not accepted early.

## Rules

1. One phase per prompt.
2. Phase reports follow the master prompt's report format.

## Examples

Phase 2 report lists tasks relpath to integrator with their evidence logs.

## Future Considerations

Add a beta phase after Phase 4 if testers exist.
