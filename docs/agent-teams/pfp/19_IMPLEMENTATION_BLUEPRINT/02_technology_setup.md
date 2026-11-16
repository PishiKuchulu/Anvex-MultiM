# Technology Setup

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Fix the toolchain so the agent does not choose versions (ADR-010, ADR-013, ADR-015).

## Scope

Build and test tooling.

## Requirements

- Language: Kotlin 2.0.21; JVM target 17.
- Build: Gradle wrapper of Anvex 4.15.2; AGP 8.7.3; KSP 2.0.21-1.0.28; versions only from the host catalog.
- Test: JUnit 4.13.2; kotlinx-coroutines-test 1.9.0.
- Android: minSdk 26, compileSdk 35, targetSdk 35.
- Standalone verification of JVM modules: kotlinc 2.0.21 with `-jvm-target 17`, JDK 17 or newer.
- Libraries: Room 2.6.1 (runtime only), Compose BOM 2024.12.01 (UI only).

## Decisions

- No other tooling is introduced.

## Rules

1. Pin versions exactly.
2. Record the actual JDK and kotlinc versions in each evidence log.

## Examples

Evidence header: `kotlinc 2.0.21, JDK 21.0.x, -jvm-target 17, 2026-11-xx`.

## Future Considerations

Move the standalone verification to CI.
