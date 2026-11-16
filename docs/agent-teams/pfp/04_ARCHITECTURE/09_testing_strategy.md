# Testing Strategy

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Make every claim about behavior provable, including in an environment without Android (ADR-019).

## Scope

agents-core and agents-fs on the plain JVM; agents-runtime and agents-ui with the Android toolchain; manual device checks.

## Requirements

- **Unit tests (JVM):** state machine, scheduler, policy, validators, merge, integrator, CAS, RelPath. JUnit 4.13.2.
- **Property tests:** random disjoint edits always merge cleanly and the result contains both edits; random overlapping edits always conflict.
- **Parity tests:** RelPath versus Anvex `PathSanitizer` on a shared corpus including traversal, bidi and NUL cases (AC-006).
- **Integration tests:** RealWorkspaceFileSystem over Anvex `RealProjectFileSystem` in a temp directory, including symlink planting (AC-008).
- **Architecture guard:** scans agents-core sources for forbidden imports (AC-019).
- **Chaos tests:** fault injection in the Integrator at every write step (AC-014); event-log replay after truncation (AC-005).
- **Android tests:** Room migrations with populated data, execution host cancellation, UI tests (AC-021, AC-022, AC-024).
- **Evidence:** each acceptance test result is stored as a text log in the evidence folder with command, date and exit code.

## Decisions

- JVM modules can be compiled and tested with kotlinc 2.0.21 outside Gradle (ADR-015); Gradle is authoritative for the Android modules. Anything not executed is labelled UNVERIFIED in the validation report.

## Rules

1. A test must fail when the behavior it guards is removed (mutation sanity check for security tests).
2. No sleeps for synchronization; use injected clocks and deterministic executors.

## Examples

`MergePropertyTest` generates 1,000 random base files and edit pairs with a fixed seed and checks both properties.

## Future Considerations

Add a fuzz target for ChangeSet parsing.
