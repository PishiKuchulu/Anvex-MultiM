# Handoff Entry

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Describe exactly how to start the implementation with a coding agent.

## Scope

The first session and the cadence afterwards.

## Requirements

1. Provide the agent with: this package, a clean copy of Anvex 4.15.2 source (no keystores or local properties), and access to a shell.
2. Send 09_PROMPTS/00_master_coding_prompt.md, then the M0 prompt, then wait for the report.
3. Send the report to the independent reviewer with 09_PROMPTS/03_review_prompts.md.
4. After accept and owner acknowledgment, send the next milestone prompt.
5. Do not give the agent the previous conversation; the package is the single source.
Readiness: the control plane states CODING_AGENT_READY for the foundation; Android milestones remain UNVERIFIED until built on real tooling.

## Decisions

- OPEN decisions ADR-028 and ADR-029 proceed with their recommended defaults.

## Rules

1. The owner remains the final approver of every milestone.
2. The agent must stop at hard stops in the master prompt.

## Examples

Session start: paste the master prompt, attach the package folder and the Anvex source, then paste the M0 prompt.

## Future Considerations

Automate the cadence with a CI-driven checklist.
