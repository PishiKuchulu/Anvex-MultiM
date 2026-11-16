# Interaction Rules

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Fix interaction behavior so similar actions behave the same everywhere.

## Scope

Touch interactions in the module's screens.

## Requirements

- Destructive actions (stop run, discard changes, rollback) require confirmation with the consequence stated.
- Pause is immediate in the UI; the state shows "Pausing…" until the runner acknowledges.
- Long operations show progress and are cancellable.
- Approve and apply are separate actions; approve never auto-applies.
- Conflicts never auto-resolve.

## Decisions

- Confirmations state the number of files and agents affected.

## Rules

1. No gesture-only actions; every action has a visible control.
2. Haptics are optional and off by default.

## Examples

Stop run dialog text: Stop 3 agents? Uncommitted workspace changes stay available for review.

## Future Considerations

Undo for apply is provided by rollback, not by a transient toast.
