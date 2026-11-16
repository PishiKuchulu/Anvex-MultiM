# Quality Requirements

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define the measurable quality bar for code and tests.

## Scope

All modules.

## Requirements

- Every acceptance test in the control plane has an automated test or a documented manual check.
- Line coverage target: agents-core 90 percent, agents-fs 85 percent; branch coverage for Integrator, merge and policy 95 percent. Coverage is measured, not assumed.
- Security components have negative tests for each threat they address.
- Zero compiler warnings treated as errors in agents-core.
- Build must be reproducible from a clean checkout with the documented commands.

## Decisions

- Coverage numbers are reported in evidence only when measured.

## Rules

1. A failing test is fixed in code, never by editing the expectation unless the specification changed.
2. Flaky tests are defects.

## Examples

Policy engine tests include a table of 40 request/decision pairs including all precedence combinations.

## Future Considerations

Add mutation testing for merge and policy.
