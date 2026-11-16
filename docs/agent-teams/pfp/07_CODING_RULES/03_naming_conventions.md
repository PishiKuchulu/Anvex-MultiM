# Naming Conventions

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Keep names consistent and avoid collisions with Anvex classes.

## Scope

Packages, classes, tests and resources.

## Requirements

- Root package `com.anvex.agents` with subpackages `core`, `fs`, `runtime`, `ui`.
- Port interfaces end with `Port` except `WorkspaceFileSystem` and `WorkspaceProvider` (ADR-003).
- Never reuse names `ProjectFileSystem`, `RealProjectFileSystem`, `ChatSession`, `ProviderClient` for new types.
- Tests mirror the class name with `Test`; property tests end with `PropertyTest`.
- Resource names start with `agents_`; strings file `strings_agents.xml`.
- IDs of requirements and decisions appear in test names as `Test_<ID>` comments for traceability.

## Decisions

- Names stay English in code; user-facing text is localized.

## Rules

1. Abbreviations only if listed in the glossary.
2. Boolean names read as predicates.

## Examples

`RealWorkspaceFileSystemTest`, `agents_run_state_running` (string key).

## Future Considerations

Reserve the `com.anvex.agents.api` package for a possible public API.
