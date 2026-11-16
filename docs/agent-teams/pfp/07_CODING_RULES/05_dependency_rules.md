# Dependency Rules

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Control third-party and host dependencies.

## Scope

Gradle dependencies of the four modules.

## Requirements

- agents-core: none beyond the Kotlin standard library.
- agents-fs: agents-core and `:core:engine` (for `ProjectFileSystem` and `PathSanitizer` only).
- agents-runtime: `:core:model`, `:core:engine`, `:core:ai`, kotlinx-coroutines 1.9.0, Room 2.6.1 with KSP 2.0.21-1.0.28.
- agents-ui: Compose BOM 2024.12.01, Material 3, lifecycle components already used by the app.
- Test: JUnit 4.13.2, kotlinx-coroutines-test 1.9.0.
- No new Maven repository; versions only from the host catalog; any new library requires an ADR and a license check.

## Decisions

- Host classes outside the list above may not be referenced; the adapters in the integration contract are the only bridge (ARC-INT-1).

## Rules

1. Dependencies are pinned to exact versions.
2. A transitive dependency change is reviewed like a direct one.

## Examples

Adding an HTTP client to agents-runtime is rejected because networking goes through the host provider (ADR-018).

## Future Considerations

Dependency updates follow the host's update cadence.
