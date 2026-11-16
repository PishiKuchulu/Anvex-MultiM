# Technical Notes

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Hold verified technical facts and known limits that implementers need.

## Scope

Facts about Anvex 4.15.2, the planning environment and tooling.

## Requirements

- **Host facts (verified in source):** ProjectFileSystem signature and implementations; RealProjectFileSystem hardening (atomic writes, canonical confinement, symlink-safe delete and walk); GitLite lacks merge and worktrees; DurableTaskEngine is serial and rejects credential-like payload keys; AnvexDatabase version 13; app and core:android use minSdk 26, compileSdk 35, JVM 17; version catalog: Kotlin 2.0.21, KSP 2.0.21-1.0.28, Room 2.6.1, OkHttp 4.12.0, coroutines 1.9.0, Compose BOM 2024.12.01, JUnit 4.13.2, AGP 8.7.3.
- **Host classes confirmed by declaration:** `SensitivePaths`, `ActionSafetyPolicy`, `PatchEngine` (objects) and `BoltStreamParser`, `ActionDispatcher` (classes) in core:engine; `FallbackProviderClient`, `ProviderClientFactory`, `ChatRequest` in core:ai; `CodebaseIndexer`, `SymbolIndexManager`, `AndroidRuntimeGateway`, `SafProjectFileSystem` in core:android; `AgentForegroundService` (foregroundServiceType dataSync) in app.
- **Verification status at hand-over:** agents-core (203 tests) and agents-fs (54 tests) were compiled with kotlinc 2.0.21 and run with JUnit 4.13.2 outside Gradle; 45 hand-written mutations were all caught by the tests. agents-runtime and agents-ui (Room, coroutines, foreground service, Compose) were written against the 4.15.2 sources but never compiled or run: they are UNVERIFIED.
- **Host finding:** PathSanitizer is not idempotent (ADR-033).
- **Known limits of snapshots:** empty directories, symlinks and mode bits are not captured; RealProjectFileSystem.walk omits symlinks.
- **Planning environment:** Kotlin 2.0.21 compiles in the planning sandbox; Maven Central and Gradle are not reachable there, so Android modules and Gradle builds are UNVERIFIED until built elsewhere.
- **PFP:** the standard is specification plus Python validators; it is not a runtime (ADR-022).

## Decisions

- Facts here must be re-verified by the implementing agent before use.

## Rules

1. A fact without a source reference is marked UNVERIFIED.
2. Update this file when the host changes.

## Examples

If Anvex later adds merge to GitLite, merge policy still stays in the core Integrator unless a new ADR changes it.

## Future Considerations

Record device measurements here after M3.
