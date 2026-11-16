# Security Architecture

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Describe the security controls of the module and how they bind to the requirements in 08_SECURITY (ARC-SEC-1, ARC-SEC-2, ARC-SEC-3).

## Scope

Controls inside the module. Existing Anvex protections (approval boundary, SensitivePaths, mutation journal) are reused, not replaced. Binding rules and the threat model are in 08_SECURITY.

## Requirements

| Control | Component | Threat | Test |
|---|---|---|---|
| RelPath validation and real-filesystem confinement | ARC-FS-1 | THREAT-001 | AC-006 |
| ChangeSet validation, limits | ARC-CORE-3 | THREAT-011, THREAT-001 | AC-009 |
| Write grants and sensitive paths | ARC-SEC-1 | THREAT-002 | AC-015 |
| Untrusted-message handling | ARC-AND-1, ARC-SEC-1 | THREAT-003 | AC-017 |
| Credential reference model | ARC-SEC-2 | THREAT-004 | AC-018 |
| Atomic apply with rollback snapshot | ARC-CORE-4 | THREAT-010, THREAT-005 | AC-014, AC-013 |
| Independent gates | ARC-CORE-5 | THREAT-006 | AC-016 |
| Budget enforcement | ARC-CORE-6 | THREAT-007 | AC-004 |
| Verified CAS reads | ARC-FS-2 | THREAT-008 | AC-007 |
| Real-path-only engine | ARC-FS-1 | THREAT-009 | AC-019, AC-020 |
| Shell through existing policy | ARC-PORT-1 | THREAT-012 | AC-015 |

## Decisions

- ADR-007, ADR-008, ADR-022, ADR-001.
- Fail-closed: when evidence or configuration is missing, the decision is DEFER or ESCALATE, never ALLOW.

## Rules

1. Controls here are binding; they refine 08_SECURITY and may not contradict it.
2. A new capability gets a threat entry before implementation.
3. Audit events are append-only and contain references, not content or secrets.

## Examples

An agent requests editing `.env`: `SensitivePathPort` marks it sensitive → DENY, audit event recorded, task state unchanged.

## Future Considerations

Signed audit chains and per-agent sandboxes beyond directory isolation are candidates after the MVP.
