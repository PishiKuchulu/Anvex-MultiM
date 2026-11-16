# Coding Agent Role

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define the role of the AI coding agent that implements this module so that its authority is clear.

## Scope

The implementing agent (a model with file and shell access) working in a copy of Anvex 4.15.2. Not to be confused with the product's runtime agents.

## Requirements

The implementing agent: reads the package in the order given in 09_PROMPTS/00_master_coding_prompt.md; implements exactly one milestone at a time; writes code and tests; runs the verification commands; records evidence; stops at every hard-stop condition and asks the owner.
It may not: change the package's requirements, decisions or milestone order; weaken a rule; mark a milestone done without evidence; edit the main Anvex source outside the integration contract (ADR-023).

## Decisions

- Authority follows the PFP authority matrix: this package outranks the agent's preferences.

## Rules

1. When a rule and a convenient shortcut conflict, the rule wins.
2. Unknowns are questions to the owner, not guesses.

## Examples

If a required Anvex API differs from what the package states, the agent reports the difference and stops that task.

## Future Considerations

A second, independent reviewer model should inspect each milestone's diff.
