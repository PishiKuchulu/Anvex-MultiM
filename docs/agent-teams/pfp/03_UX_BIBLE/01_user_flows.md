# User Flows

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define end-to-end journeys so no state is forgotten.

## Scope

MVP flows.

## Requirements

1. **Create team → add agents → set permissions → add tasks → start run.**
2. **Run → monitor → conflict → resolve → apply → verified.**
3. **Same task to two agents → compare → choose one → apply.**
4. **Budget warning at 80 percent → continue or stop.**
5. **App killed during a run → reopen → run shown as interrupted → resume or discard.**
6. **Rollback:** pick an applied change → restore its pre-apply snapshot.

## Decisions

- Flow 5 is mandatory because Android may kill the process (PRD-FR-22).

## Rules

1. Every flow ends in an explicit user-visible state.
2. No flow applies changes without the gate shown to the user.

## Examples

Flow 2 variant: reviewer rejects with findings; the task becomes `RevisionRequired` and the author agent is re-queued within retry limits.

## Future Considerations

Add a guided first-run flow after beta feedback.
