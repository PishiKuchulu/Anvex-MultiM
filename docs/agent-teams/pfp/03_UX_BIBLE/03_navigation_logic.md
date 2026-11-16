# Navigation Logic

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define how users move between screens and how the module attaches to Anvex navigation.

## Scope

Navigation inside the module and its single entry point.

## Requirements

- One entry route from the Anvex project screen: "Agent Teams".
- Back stack: Teams list → Team detail → (Agent editor | Task board | Run monitor → Diff/Conflicts/Review).
- Deep links from notifications open the Run monitor of the relevant run.
- The module adds exactly one route to the host navigation graph (ADR-023).

## Decisions

- Notification taps always land on a screen that shows the pending decision.

## Rules

1. System back never discards unsaved agent edits silently.
2. A run keeps going when the user leaves the screen; the foreground notification reflects it.

## Examples

Notification "Agent B finished, review needed" opens Review for that task.

## Future Considerations

Add a project-level activity timeline entry in the host after integration.
