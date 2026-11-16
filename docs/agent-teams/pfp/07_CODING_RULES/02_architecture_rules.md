# Architecture Rules

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Make the structure in 04_ARCHITECTURE enforceable by code.

## Scope

Module boundaries and layering.

## Requirements

1. Dependency direction ui → runtime → fs → core only; reverse imports fail the build or the guard test.
2. agents-core: Kotlin standard library only (ADR-009).
3. Forbidden in agents-core and in the engine logic of agents-fs: `java.io.File`, `java.nio.file`, `android.net.Uri`, `DocumentFile`, `ContentResolver` (AC-019). agents-fs reaches files only through the injected `ProjectFileSystem`.
4. Only the Integrator writes to the integration workspace.
5. Authorization lives in PolicyEngine and the Integrator, never in ports (ADR-007).
6. The composition root passes only real-path `ProjectFileSystem` implementations; passing `SafProjectFileSystem` is rejected (ADR-001).
7. No class from `ChatSession` or `HarnessBridge` is referenced (ADR-021).

## Decisions

- The architecture guard is part of the test suite and of the milestone exit gates.

## Rules

1. Rule changes require a decision-log entry and an ADR.
2. Exceptions are not granted per file.

## Examples

A pull request importing `java.io.File` in agents-core fails AC-019.

## Future Considerations

Gradle module visibility checks can replace part of the guard later.
