# Updates Process

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define how the package changes without losing consistency.

## Scope

All documents and the control plane.

## Requirements

1. Propose the change with the affected IDs.
2. Update documents and PFP_CONTROL_PLANE.json together; keep IDs stable.
3. Run the PFP validator; the verdict must be APPROVED.
4. Add a decision-log and change-history entry.
5. Re-issue the affected prompts.
Changes to requirements need a scope decision (V6). Changes to LOCKED decisions need the owner's approval.

## Decisions

- The folder set of the package is fixed by the PFP standard and is not changed.

## Rules

1. No silent edits.
2. A failing validator blocks the update.

## Examples

Raising the default concurrency cap after measurements: resolve ADR-028, update the prompt for M3, run the validator, log the change.

## Future Considerations

Add a pre-commit hook that runs the validator.
