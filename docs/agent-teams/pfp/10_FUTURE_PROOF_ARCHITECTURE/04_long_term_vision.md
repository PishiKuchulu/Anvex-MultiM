# Long-Term Vision

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

State the direction and what must stay true.

## Scope

Long-term direction of the module.

## Requirements

The long-term goal is a coordinated engineering system in which several models challenge each other, detect errors independently and produce safer code than one agent. What must stay true: independent verification, user control, fail-closed policy, real-path workspaces, provider independence, no mandatory cloud service (PRD-FR-30, PRD-FR-25).

## Decisions

- Autonomy grows only as measured trust grows (ADR-025).

## Rules

1. Autonomy never removes the user's final approval for release.
2. The control plane schemaVersion is the machine-readable contract for future validation.

## Examples

A future manager that proposes plans accepted without edits in most benchmark objectives may earn limited autonomy for low-risk tasks.

## Future Considerations

Project-level knowledge evolution through reviewed memory entries.
