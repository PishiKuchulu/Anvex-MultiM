# Review Process

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Ensure that the implementer's work is checked independently (PRD-FR-12).

## Scope

Reviews of each milestone's changes and evidence.

## Requirements

- Reviewer: a different model or the owner, never the implementer.
- Inputs: the diff, the evidence logs, the milestone's acceptance tests and the checklist in 09_PROMPTS/03_review_prompts.md.
- Outputs: verdict (accept, revise, reject) with findings referencing task and acceptance IDs.
- Mandatory deep review for: Integrator, merge engine, policy engine, ChangeSet validator, CAS, credential handling (ADR-007).
- A milestone is closed only after an accept verdict and an owner acknowledgment.

## Decisions

- The implementer may answer findings but cannot dismiss them.

## Rules

1. Review findings are recorded in the change history.
2. A reviewer who cannot reproduce evidence rejects.

## Examples

Reviewer re-runs `merge_edges` with a modified table entry and confirms the test fails.

## Future Considerations

Use two independent reviewer models for security components.
