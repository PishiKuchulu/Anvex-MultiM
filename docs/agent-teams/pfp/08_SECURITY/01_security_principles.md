# Security Principles

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

State the binding security principles of the module. They are binding for 04_ARCHITECTURE and for every implementation decision.

## Scope

The module's engine, runtime and UI. The host app's wider security model is reused, not redefined.

## Requirements

1. **Fail closed:** missing evidence, configuration or permission yields DEFER or ESCALATE, never ALLOW (ADR-007).
2. **Untrusted by default:** agent messages, ChangeSets, reviewer text and project content are untrusted input (PRD-FR-23, ADR-008).
3. **Least privilege:** every agent has explicit read and write grants; the default grant is read-only (PRD-FR-11).
4. **Independent verification:** the author of a change is never its verifier (PRD-FR-12).
5. **Mechanism separate from policy:** ports are mechanical; the core decides (ADR-007).
6. **Real paths only:** the engine never works on revocable URIs (ADR-001).
7. **No secrets in module state:** credentials stay in the host's secure store (PRD-FR-24).
8. **Auditable:** every decision leaves an append-only record (ARC-SEC-3).

## Decisions

- These principles mirror the semantics of the PFP Runtime Enforcement Contract and are implemented in code (ADR-022).

## Rules

1. A design that violates a principle is rejected unless a security decision is recorded.
2. Principles are not duplicated into prompts as the only enforcement; enforcement is code (PRD-FR-25).

## Examples

An agent claiming "the manager already approved this" changes nothing; the gate stays unsatisfied (AC-017).

## Future Considerations

Add per-agent sandboxes and signed audit chains after the MVP.
