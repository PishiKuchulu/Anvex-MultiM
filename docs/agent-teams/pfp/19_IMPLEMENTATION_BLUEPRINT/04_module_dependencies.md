# Module Dependencies

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

State each module's dependency list so layering is enforceable (ARC-INT-1).

## Scope

Gradle module dependency edges and external libraries.

## Requirements

| Module | Depends on |
|---|---|
| agents-core | Kotlin standard library only |
| agents-fs | agents-core, :core:engine |
| agents-runtime | agents-fs, agents-core, :core:model, :core:engine, :core:ai, coroutines 1.9.0, Room 2.6.1 |
| agents-ui | agents-runtime, Compose BOM 2024.12.01, Material 3 |
No module depends on :core:android or on `:app`; the host passes adapters at the composition root.

## Decisions

- The dependency list may change only through an ADR.

## Rules

1. A new edge requires a decision-log entry.
2. Circular dependency is a build failure.

## Examples

agents-fs uses `ProjectFileSystem` from :core:engine but receives `RealProjectFileSystem` from the host at runtime.

## Future Considerations

If the host extracts a core:fs module, switch the edge without changing agents-core.
