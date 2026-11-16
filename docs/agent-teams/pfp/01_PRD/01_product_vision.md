# Product Vision

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Anvex Agent Teams lets several AI agents, each with its own provider, model, role and permissions, work on one Anvex project at the same time, while every result is checked independently before it reaches the user's files. It exists because a single coding agent cannot cross-check itself: one model's blind spot becomes the project's bug. The product goal is higher quality, security and maintainability, not a larger number of agents.

## Scope

In scope: the multi-agent engine (tasks, scheduling, isolated workspaces, change validation, merging, review gates, budgets), its persistence, its execution host and its Compose UI, delivered as a separable module (`anvex-agents`) and integrated into Anvex 4.15.2 at the end (PRD-FR-47). Out of scope for this package: changes to the existing single-agent chat path, the harness web UI, and any PC or cloud component.

## Requirements

The vision requirements, verbatim from the product owner's specification:

| ID | Requirement | Priority |
|---|---|---|
| PRD-FR-1 | Evolve Anvex into a multi-agent development environment | must |
| PRD-FR-2 | Multiple agents collaborate on one project with different models/providers/roles | must |
| PRD-FR-29 | Compatible with existing Anvex architecture | must |
| PRD-FR-30 | No unnecessary external infrastructure | must |
| PRD-FR-47 | Build the module completely, integrate at the end | must |

The product has two operating modes. Mode B (user-managed team) is the foundation and ships first. Mode A (autonomous manager) is delivered as a propose-only manager in milestone RDM-M-5 and full autonomy is deferred (ADR-025, scope decision SD-003).

## Decisions

- ADR-009: decision logic is pure JVM so it can be proven without a device.
- ADR-023: the main source is touched only at listed contract points.
- ADR-025: autonomy follows proof on user-managed teams.

## Rules

1. The module never weakens existing Anvex safety behavior (approval boundary, sensitive-path protection, mutation journal).
2. No feature may require a PC, a backend or a cloud account other than the model provider the user already configured.
3. Quality claims must be backed by executed tests; unverified parts are labelled as unverified.

## Examples

A user selects three agents: a Kotlin developer on provider A, an independent implementer on provider B, and a reviewer on provider C. The two implementations run in separate workspaces; the reviewer compares both diffs; the user picks one and the system applies only that result.

## Future Considerations

Dynamic team formation, multiple managers, cross-team collaboration and continuous quality monitoring are expected later; the port-based architecture (ARC-PORT-1, ARC-CORE-8) exists so they can be added without redesigning the core (PRD-FR-28).
