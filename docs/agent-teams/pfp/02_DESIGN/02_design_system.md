# Design System

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

List the design tokens and usage rules the module relies on.

## Scope

Tokens are inherited from the Anvex theme (app/ui/theme); this document lists only the semantic additions.

## Requirements

| Semantic token | Meaning | Maps to |
|---|---|---|
| status.running | agent executing | theme primary |
| status.blocked | waiting on dependency or approval | theme tertiary |
| status.conflict | merge conflict | theme error |
| status.verified | independently reviewed and accepted | theme secondary |
Spacing uses the Anvex 4 dp grid; corner radius follows existing cards; minimum touch target 48 dp.

## Decisions

- Semantic status tokens are defined once in agents-ui and mapped to the theme.

## Rules

1. Never put status meaning into color only.
2. Monospace font for paths, hashes and diffs.

## Examples

`status.conflict` renders as an error-container chip labelled "Conflict" / «تعارض».

## Future Considerations

Dynamic color is supported through the existing theme.
