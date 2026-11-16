# Development Workflow

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Give the implementing agent a repeatable loop that produces evidence.

## Scope

One milestone at a time.

## Requirements

1. Read the milestone row in 05_ROADMAP/03_milestones.md and the linked tasks and acceptance tests.
2. Read only the documents those tasks reference (progressive disclosure).
3. Write the acceptance tests first when feasible.
4. Implement; compile; run tests; capture command output.
5. Run the architecture guard and the full test set of completed milestones.
6. Write the evidence log and update the change history.
7. Report: what was done, what is verified, what is UNVERIFIED, what is blocked.
Standalone verification command for JVM modules: `kotlinc -jvm-target 17` with the sources and the required jars, then run the test entry point; the exact command is recorded in the evidence log.

## Decisions

- Android modules cannot be built in constrained sandboxes; their acceptance is deferred to a real build and the report says so (ADR-015).

## Rules

1. Never report success from reading code; only from executed commands.
2. Keep commits small and tied to task IDs.

## Examples

Evidence file: `evidence/M2/merge_edges.log` with the command, JDK version and the pass count.

## Future Considerations

A CI job can run the same loop automatically.
