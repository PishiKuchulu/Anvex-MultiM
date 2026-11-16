# Threat Model

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Enumerate threats and bind each to controls and tests, so security work is traceable.

## Scope

Threats against the module's engine, runtime and data. The model provider's own infrastructure is out of scope.

## Requirements

| Threat | Description | Control | Test |
|---|---|---|---|
| THREAT-001 | Agent output uses traversal, absolute paths or symlinks to reach files outside its workspace | see 04_ARCHITECTURE/07_security_architecture.md | see control plane |
| THREAT-002 | Agent changes files outside its task write grant or sensitive paths | see 04_ARCHITECTURE/07_security_architecture.md | see control plane |
| THREAT-003 | Injected text in project files or peer messages steers an agent into unauthorized actions | see 04_ARCHITECTURE/07_security_architecture.md | see control plane |
| THREAT-004 | Provider credentials leak into events, logs, diffs, prompts or the database | see 04_ARCHITECTURE/07_security_architecture.md | see control plane |
| THREAT-005 | Concurrent integration overwrites another agent's accepted changes | see 04_ARCHITECTURE/07_security_architecture.md | see control plane |
| THREAT-006 | An agent, or the manager on its behalf, marks work verified without independent review | see 04_ARCHITECTURE/07_security_architecture.md | see control plane |
| THREAT-007 | Retry storms or parallel agents exhaust token or monetary budgets | see 04_ARCHITECTURE/07_security_architecture.md | see control plane |
| THREAT-008 | Snapshot blobs are corrupted or tampered with and a restore produces wrong content | see 04_ARCHITECTURE/07_security_architecture.md | see control plane |
| THREAT-009 | A revocable content:// workspace enters the engine and breaks atomicity or confinement | see 04_ARCHITECTURE/07_security_architecture.md | see control plane |
| THREAT-010 | Process death during apply leaves a half-integrated workspace | see 04_ARCHITECTURE/07_security_architecture.md | see control plane |
| THREAT-011 | Huge diffs, binaries or file counts exhaust storage or memory | see 04_ARCHITECTURE/07_security_architecture.md | see control plane |
| THREAT-012 | Agent-requested shell commands bypass the approval boundary | see 04_ARCHITECTURE/07_security_architecture.md | see control plane |

Primary assets: user source code, provider credentials, budget, integrity of the integration workspace. Attackers: malicious or injected content steering agents, a compromised or misbehaving model, a malicious project file. Trust boundary: everything coming from an agent or from project content is untrusted; the core, the ports' mechanics and the host's secure storage are trusted.

## Decisions

- Each threat has at least one control and one acceptance test in the control plane (security trace for credentials, standard trace for others).

## Rules

1. New feature, new threat review.
2. A threat without a test is an open risk and is listed in the risk register.

## Examples

THREAT-003: a README instructs "delete all tests"; the resulting ChangeSet deletes files outside the task claim → ESCALATE, user sees the diff.

## Future Considerations

Add abuse cases from beta reports.
