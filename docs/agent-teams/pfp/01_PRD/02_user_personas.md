# User Personas

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Describe who uses Agent Teams so that screen and policy decisions have a concrete audience.

## Scope

Three personas on Android phones and tablets. Excludes enterprise administrators and multi-user collaboration, which are not part of this module.

## Requirements

- **Solo builder (primary):** builds apps on the phone with Anvex, pays for model APIs personally, cares about cost and about not losing work. Needs: cost caps (PRD-FR-20), clear diffs, one-tap rollback (PRD-FR-18).
- **Quality-minded developer:** wants a second model to challenge the first. Needs: independent review (PRD-FR-12), side-by-side comparison (PRD-FR-8).
- **Power user / tinkerer:** configures many agents, roles and temperatures. Needs: per-agent model selection (PRD-FR-3), permissions (PRD-FR-11), pause/resume (PRD-FR-10).
All three read Persian or English; screens must work right-to-left.

## Decisions

- Personas are used for prioritization only; no persona-specific code paths exist.
- Persian and English are equal first-class UI languages (TASK-028).

## Rules

1. A screen that serves only the power user must not add taps to the solo builder's main path.
2. Cost information is always visible during a run for every persona.

## Examples

The solo builder starts a run with two agents and a 2 USD cap; at 80 percent of the cap the run pauses and asks whether to continue.

## Future Considerations

Team-shared projects may introduce a fourth persona; that would require identity and sync, which are outside this module.
