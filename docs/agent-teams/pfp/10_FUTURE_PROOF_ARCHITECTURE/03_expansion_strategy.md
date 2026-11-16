# Expansion Strategy

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Map each future capability from the original specification to the extension point that will carry it (PRD-FR-28).

## Scope

Capabilities from section 13 of the specification.

## Requirements

| Capability | Extension point |
|---|---|
| Dynamic agent team formation | registry plus scheduler commands (ARC-CORE-8) |
| Agent capability discovery and recruitment | profile metadata in the registry |
| Multiple independent managers | several manager task types sharing the event log |
| Cross-team collaboration | event log sharing through a new port |
| Agent performance evaluation | analytics over usage and gate outcomes |
| Automated architecture discussions | new task type with competing outputs |
| Competing implementation strategies | already supported by competitor assignments (ADR-024) |
| Automated regression testing | task type that runs commands through ShellPort |
| Continuous code quality monitoring | scheduled runs |
| Long-running workflows | persisted event log and recovery (PRD-FR-22) |
| Human-in-the-loop decisions | ApprovalPort and gates |
| Release preparation and verification | task types plus gates |

## Decisions

- None of these is built now; each must fit the existing ports or add a port without changing the scheduler contract.

## Rules

1. A capability that needs a scheduler contract change requires a new ADR.
2. Extension must not weaken gates (PRD-FR-14).

## Examples

Regression testing is added as a task type that runs the project's test command in the agent's workspace under ShellPort.

## Future Considerations

Review this table after M6.
