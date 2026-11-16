# Components

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Name the reusable UI components so screens are assembled consistently.

## Scope

Compose components inside agents-ui.

## Requirements

- **AgentCard:** name, role, provider/model, state chip, current activity, tokens, cost, controls (pause, resume, stop).
- **TaskRow:** title, assignee(s), state chip, dependency count, files claimed.
- **DiffView:** unified and side-by-side diff of a ChangeSet, per-file collapse, conflict markers.
- **CompareSheet:** two or more competing ChangeSets with summary metrics (files, lines, review verdict).
- **BudgetBar:** tokens and cost against caps with warning threshold at 80 percent.
- **GateBanner:** shows which mandatory gate is unmet and who may satisfy it.
- **ConflictPanel:** lists conflicting hunks with actions: keep current, take agent, edit manually, reassign.

## Decisions

- Components take immutable UI models; they never call engine classes directly.

## Rules

1. Components have previews in English and Persian.
2. Components expose no destructive action without a confirmation step.

## Examples

GateBanner text: "Needs independent review — the author cannot approve" / «نیاز به بازبینی مستقل — نویسنده نمی‌تواند تأیید کند».

## Future Considerations

A dependency graph component is planned after the list-based MVP.
