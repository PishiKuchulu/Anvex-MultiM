# Feature Implementation Prompts

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Give one ready-to-paste prompt per milestone so the implementing agent receives exactly its scope.

## Scope

Milestones M0 to M6. Each prompt is used after the master prompt.

## Requirements

Prompts (send one at a time, after the previous milestone is accepted):

```text
**Milestone M0 (RDM-M-0) — baseline**
Read: README.md, PFP_MANIFEST.md, 11_LIVING_DOCUMENTATION/04_technical_notes.md
Deliver: validator verdict log for this package (TASK-001); record of the Anvex 4.15.2 source inventory hash in 11_LIVING_DOCUMENTATION/02_change_history.md (TASK-002).
Pass: validator verdict APPROVED; hash recorded.
Stop and ask if: a package statement conflicts with the Anvex source, or an acceptance test cannot be made to pass without changing a rule.
```

```text
**Milestone M1 (RDM-M-1) — pure core**
Read: 04_ARCHITECTURE/01, 03, 05; 08_SECURITY/04; 07_CODING_RULES/*
Deliver: modules agents-core and agents-fs scaffold (TASK-003); domain and state machine (TASK-004); event log with replay (TASK-005); scheduler (TASK-006); budget ledger (TASK-007); ports and fakes (TASK-008); policy engine (TASK-009); architecture guard test (TASK-010).
Pass: AC-001, AC-002, AC-003, AC-004, AC-005, AC-019, AC-032. Scheduler contract: next(state, event) returns (state, commands), no I/O, no clock, no randomness.
Stop and ask if: a package statement conflicts with the Anvex source, or an acceptance test cannot be made to pass without changing a rule.
```

```text
**Milestone M2 (RDM-M-2) — workspaces and merge**
Read: 04_ARCHITECTURE/03 (path model, snapshots, merge table, integrator); 08_SECURITY/02, 04; Decisions ADR-002, ADR-005, ADR-006
Deliver: RelPath with parity tests against PathSanitizer (TASK-011); RealWorkspaceFileSystem over an injected ProjectFileSystem (TASK-012); CAS store (TASK-013); fork (TASK-014); ChangeSet validator (TASK-015); merge engine implementing the edge-case table exactly (TASK-016); Integrator (TASK-017).
Pass: AC-006, AC-007, AC-008, AC-009, AC-010, AC-011, AC-012, AC-013, AC-014, AC-015.
Also write property tests for merge (disjoint edits always merge, overlapping always conflict) and fault-injection tests for the Integrator. Test RealWorkspaceFileSystem against the real RealProjectFileSystem including a planted symlink.
Stop and ask if: a package statement conflicts with the Anvex source, or an acceptance test cannot be made to pass without changing a rule.
```

```text
**Milestone M3 (RDM-M-3) — runtime**
Read: 04_ARCHITECTURE/04, 05, 06; 08_SECURITY/03; Decisions ADR-016, ADR-021, ADR-018
Deliver: agents.db with entities, DAOs, migrations and exported schema snapshots (TASK-018); AgentRunner adapter and ProviderPort (TASK-019); execution host with foreground service and cancellation propagation (TASK-020); adapters of the integration contract (TASK-021).
Pass: AC-021, AC-022, AC-018, AC-023, AC-029, AC-020. Android items that cannot be executed are labelled UNVERIFIED.
Decide the default concurrency cap with measurements (ADR-028); until then use 3.
Stop and ask if: a package statement conflicts with the Anvex source, or an acceptance test cannot be made to pass without changing a rule.
```

```text
**Milestone M4 (RDM-M-4) — UI**
Read: 02_DESIGN/*, 03_UX_BIBLE/*, 04_ARCHITECTURE/02
Deliver: screens, diff and conflict views, approvals, budget and notification surfaces, English and Persian strings with RTL (TASK-025, TASK-026, TASK-027, TASK-028).
Pass: AC-024, AC-025. No business logic in composables.
Stop and ask if: a package statement conflicts with the Anvex source, or an acceptance test cannot be made to pass without changing a rule.
```

```text
**Milestone M5 (RDM-M-5) — assurance**
Read: 04_ARCHITECTURE/03 (gates), 08_SECURITY/04; Decisions ADR-025, ADR-029
Deliver: reviewer task type and gate enforcement (TASK-029); propose-only manager that outputs a validated task graph requiring explicit user approval (TASK-030).
Pass: AC-016, AC-017, AC-026. Review policy default: independent reviewer always required (ADR-029).
Stop and ask if: a package statement conflicts with the Anvex source, or an acceptance test cannot be made to pass without changing a rule.
```

```text
**Milestone M6 (RDM-M-6) — integration**
Read: 04_ARCHITECTURE/10; Decisions ADR-023
Deliver: integration into the main source strictly at the contract points (TASK-031); release audit results and the manual device checklist (TASK-032).
Pass: AC-027, AC-028. Show `git diff --stat` proving that only contract areas changed.
Stop and ask if: a package statement conflicts with the Anvex source, or an acceptance test cannot be made to pass without changing a rule.
```

## Decisions

- One milestone per prompt; the agent may not start the next one by itself (RDM-M-1–RDM-M-6).

## Rules

1. Prompts reference IDs; if the package changes, the prompts are regenerated.
2. The owner pastes the agent's report to the independent reviewer before the next prompt.

## Examples

After M2 the owner sends the agent's report and diff to a different model with the review prompt.

## Future Considerations

Prompts for post-M6 work (reviewer automation, manager autonomy, SAF gateway) will be added when those decisions are resolved.
