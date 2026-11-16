# Design Vision

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

State the visual and interaction intent so the Agent Teams screens feel native to Anvex.

## Scope

Visual direction for the Compose screens of the module; it does not redefine the Anvex theme.

## Requirements

- Use the existing Anvex Material 3 theme and typography; do not introduce a new palette.
- Information density over decoration: the main screen answers "who is doing what, what did it change, what does it cost".
- Status is shown by text plus icon, never by color alone.
- Layouts must mirror for Persian (RTL) and scale to tablets with a two-pane layout.

## Decisions

- Reuse Anvex components (cards, top bars, bottom sheets) before creating new ones.

## Rules

1. No hard-coded colors; theme tokens only.
2. Every status has a text label in both languages.

## Examples

A running agent card shows the agent name, model, state label, current file and live token counter, with pause and stop buttons.

## Future Considerations

A dedicated design review is advisable once real runs exist.
