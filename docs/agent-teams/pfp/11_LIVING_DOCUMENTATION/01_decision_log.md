# Decision Log

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Single index of decisions with their classification (V7).

## Scope

All architecture, security, product and governance decisions of the module; the full text is in the control plane.

## Requirements

| ID | Decision | Class | Area |
|---|---|---|---|
| ADR-001 | SAF is allowed only at an import/export gateway; the engine core never sees content:// URIs | LOCKED | architecture |
| ADR-002 | WorkspaceFileSystem delegates to (is mounted on) the existing ProjectFileSystem; it is not a parallel implementation | LOCKED | architecture |
| ADR-003 | Port is named WorkspaceFileSystem; implementation RealWorkspaceFileSystem | LOCKED | architecture |
| ADR-004 | Core uses a root-relative RelPath model; no absolute paths; version control and shell are separate ports | LOCKED | architecture |
| ADR-005 | Snapshot = content-addressed store (CAS) plus manifest; a hash alone is never a snapshot | LOCKED | architecture |
| ADR-006 | MVP merge policy: line-based three-way merge, automatic only for non-overlapping regions, overlap is a conflict | LOCKED | architecture |
| ADR-007 | Ownership and permissions are enforced by the core Policy Engine and Integrator, not by ports | LOCKED | security |
| ADR-008 | Every agent output is untrusted input and is validated before it can be applied | LOCKED | security |
| ADR-009 | agents-core is pure JVM with zero Anvex dependencies; the scheduler is a deterministic state machine | LOCKED | architecture |
| ADR-010 | Language: Kotlin 2.0.21 | LOCKED | language |
| ADR-011 | Framework: Kotlin standard library and kotlinx-coroutines 1.9.0 (runtime only); Jetpack Compose Material 3 via BOM 2024.12.01 (UI only) | LOCKED | framework |
| ADR-012 | Platform: Android minSdk 26, compileSdk 35, targetSdk 35; core and fs modules target the JVM | LOCKED | platform |
| ADR-013 | Build: Gradle with the Anvex version catalog (AGP 8.7.3, KSP 2.0.21-1.0.28) | LOCKED | build |
| ADR-014 | Runtime: JVM 17 bytecode for core modules; Android ART for runtime/ui modules | LOCKED | runtime |
| ADR-015 | Toolchain: JDK 17 for Gradle builds; kotlinc 2.0.21 for standalone verification of JVM modules | LOCKED | toolchain |
| ADR-016 | Database: Room 2.6.1 with KSP, in a separate database file agents.db | LOCKED | database |
| ADR-017 | Dependency injection: manual constructor injection through an AgentsGraph; no DI framework | LOCKED | di |
| ADR-018 | Networking: the module opens no network connections; model calls go through the host ProviderClient (OkHttp 4.12.0 in core:ai) | LOCKED | networking |
| ADR-019 | Testing: JUnit 4.13.2 and kotlinx-coroutines-test 1.9.0; JVM modules must be runnable without Android | LOCKED | testing |
| ADR-020 | The module has its own parallel scheduler; DurableTaskEngine is used only as a pattern | LOCKED | architecture |
| ADR-021 | The module does not reuse ChatSession; the runner composes ProviderClient, BoltStreamParser, ActionDispatcher and PatchEngine | LOCKED | architecture |
| ADR-022 | PFP is a development-time standard; the in-app Policy Engine implements the semantics of the PFP Runtime Enforcement Contract | LOCKED | security |
| ADR-023 | Integration touches the main source only at the listed contract points | LOCKED | architecture |
| ADR-024 | Competing implementations of one task are compared by the user; they are never auto-merged with each other | LOCKED | product |
| ADR-025 | Autonomous Project Manager is DEFERRED; milestone 5 ships a manager that only proposes a plan | DEFERRED | product |
| ADR-026 | Agent profile import/export is DEFERRED | DEFERRED | product |
| ADR-027 | SAF import/export gateway is DEFERRED; SAF-backed projects are unsupported in v1 | DEFERRED | architecture |
| ADR-028 | Default maximum concurrent agents | OPEN | governance |
| ADR-029 | Default review policy for low-risk changes | OPEN | governance |
| ADR-030 | The Orchestrator that executes scheduler commands lives in agents-core; the Android layer is only an asynchronous shell around it | LOCKED | architecture |
| ADR-031 | Events are persisted write-ahead as canonical JSON and the hash chain covers that JSON, not toString | LOCKED | architecture |
| ADR-032 | After a restart interrupted work is redone: a Requeue event returns the task to RevisionRequired without consuming an attempt; a write-ahead apply journal repairs a half-applied project | LOCKED | architecture |
| ADR-033 | RelPath accepts only the canonical form: a string is valid exactly when the host PathSanitizer returns it unchanged and it is at most 4096 characters | LOCKED | security |
| ADR-034 | agents-fs is an Android library module only so that its tests can use the host RealProjectFileSystem; its main code uses no Android API | LOCKED | build |

## Decisions

- Log entries LIV-D-001 to LIV-D-003 below record tooling and planning notes.
- **LIV-D-001** (2026-11-05): The product owner approved the six planning items: naming (ADR-003), no absolute paths (ADR-004), delegation to ProjectFileSystem (ADR-002), content-backed snapshots (ADR-005), conservative merge (ADR-006) and policy in the core (ADR-007).
- **LIV-D-002**: The PFP validator's security-trace stage vocabulary is cryptographic (authentication, key derivation, key wrapping, session unlock, encryption/decryption). Only the credentials requirement is registered as security-sensitive; other security requirements are traced through standard traces and threat IDs. This is a limitation of the tool, not a relaxation of the controls.
- **LIV-D-003**: SAF-backed projects are unsupported in v1 (ADR-027).

## Rules

1. Every decision has exactly one class: LOCKED, OPEN or DEFERRED.
2. OPEN decisions carry a recommended default and an owner.

## Examples

ADR-028 stays OPEN until M3 measurements; implementation uses 3.

## Future Considerations

Move resolved OPEN decisions to LOCKED with the measurement as rationale.
