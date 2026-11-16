# Privacy Rules

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define what user data leaves the device and under what control.

## Scope

Data sent to model providers and data kept locally.

## Requirements

- Only context selected for the task is sent to a provider (PRD-FR-26); the whole project is not sent by default.
- The UI shows which provider receives what kind of data (file excerpts, diffs) before a run starts.
- No telemetry leaves the device from this module; there is no backend (PRD-FR-30).
- Sensitive paths are never included in context.
- With several providers in one team, the user can see which agent uses which provider.

## Decisions

- Provider-side retention is governed by each provider's terms and is outside the module.

## Rules

1. A new data flow requires a privacy review and a documentation update.
2. Prompts never contain credentials or personal data beyond what is in the selected context.

## Examples

Starting a run with two providers shows: "Agent A → Provider X: 14 file excerpts; Agent B → Provider Y: 9 file excerpts".

## Future Considerations

Add per-project provider allow-lists.
