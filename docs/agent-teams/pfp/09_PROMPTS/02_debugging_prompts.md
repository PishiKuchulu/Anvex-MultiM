# Debugging Prompts

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Provide a disciplined prompt for failing builds or tests so that fixes do not weaken the package.

## Scope

Any failing acceptance test or compile error.

## Requirements

```text
A test or build step failed. Do not change tests, rules or decisions to make it pass.
1) Show the exact failing command and the full error output.
2) State the expected behavior and cite the requirement, decision or acceptance ID it comes from.
3) Reproduce with the smallest possible input; show the output.
4) Identify the root cause in the code (file and line) before editing.
5) Fix the code; re-run the failing test and then the whole suite of completed milestones; show the real output.
6) If the specification itself seems wrong, stop and report it as a question; do not patch around it.
```

## Decisions

- Test edits are allowed only to correct a test that contradicts the package, and must be reported as a deviation.

## Rules

1. Every fix is accompanied by a regression test.
2. Report the failing command and the passing command with real output.

## Examples

A merge test fails for adjacent hunks: the fix changes the adjacency check in the merge engine; the table in 04_ARCHITECTURE/03 stays unchanged.

## Future Considerations

Add a bisect helper script.
