# AI Pipeline Architecture

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Describe how an agent turn is built, executed and turned into a ChangeSet, independent of model or provider (ARC-AND-1, ARC-CORE-7, PRD-FR-25).

## Scope

The AgentRunner adapter and the reviewer flow. Prompt wording lives in 09_PROMPTS.

## Requirements

1. **Context** (PRD-FR-26): `ContextPort` returns task-relevant files and symbols within the agent's token budget; the entire project is never sent by default.
2. **Request:** `ProviderPort` returns a host `ProviderClient` for the agent's provider reference and a `ChatRequest` with the agent's model and temperature.
3. **Output protocol:** the agent emits Anvex bolt actions; `BoltStreamParser` and `ActionDispatcher` apply them to the agent's fork through a `ProjectFileSystem` view of that fork. `PatchEngine` handles SEARCH/REPLACE edits.
4. **ChangeSet extraction:** after the turn loop ends, the fork is compared with its base manifest.
5. **Reviewer:** receives the ChangeSet, task, acceptance criteria and read-only tools; returns a structured verdict validated against a schema; free text in the verdict is informational only.
6. **Usage:** each response's usage is reported to `UsagePort` and the ledger (ARC-CORE-6).
Rate limits and retries are handled by the existing `FallbackProviderClient` and by the ledger's retry policy; the scheduler never retries blindly (TASK-007).

## Decisions

- ADR-021: the runner composes engine building blocks directly.
- Model choice is data in the agent profile; no code path depends on a model name (ADR-018).

## Rules

1. Agent text is never used as a path, command, or policy input without validation (ADR-008).
2. Shell requests go through `ShellPort` and the existing approval policy (THREAT-012).

## Examples

Reviewer verdict JSON: `{"task":"T-12","decision":"reject","findings":[{"file":"a.kt","line":40,"text":"null check missing"}]}`; a verdict naming another task is discarded.

## Future Considerations

Competing-strategy evaluation and automated regression runs can be added as new task types.
