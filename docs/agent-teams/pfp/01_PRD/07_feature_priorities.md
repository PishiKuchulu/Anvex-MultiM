# Feature Priorities

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Rank features so that implementation order follows value and risk.

## Scope

Priorities for features inside this module only.

## Requirements

| Rank | Feature | Why | Milestone |
|---|---|---|---|
| 1 | Scheduler, domain, policy (pure JVM) | Everything depends on correct decisions | RDM-M-1 |
| 2 | Isolated workspaces, CAS, ChangeSet validation, Integrator | Prevents lost work and unsafe writes | RDM-M-2 |
| 3 | Persistence, runner, host | Makes runs real and recoverable | RDM-M-3 |
| 4 | UI comparison and approvals | Gives the user control | RDM-M-4 |
| 5 | Independent review gates, propose-only manager | Quality and governance | RDM-M-5 |
| 6 | Integration into main source | Delivery | RDM-M-6 |

## Decisions

- Safety-bearing components precede visible features: a UI over an unsafe engine is not shipped.

## Rules

1. Priority order may change only by a decision-log entry.
2. A lower-ranked feature may not start before the acceptance tests of higher-ranked ones pass, except UI mock-ups.

## Examples

If scheduler tests fail, UI work continues only on static mock-ups.

## Future Considerations

Re-rank after real-device cost and latency measurements.
