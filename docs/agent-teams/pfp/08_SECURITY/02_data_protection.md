# Data Protection

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define how project data, snapshots and logs are protected inside the module.

## Scope

Data at rest in agents.db, the CAS and agent workspaces; data in memory.

## Requirements

- All module data lives in app-private storage under the agents root; nothing is written to shared storage.
- CAS blobs are verified by hash on every read; a mismatch aborts the operation (AC-007).
- Workspaces and blobs are deleted when a team is deleted; CAS garbage collection removes unreferenced blobs.
- Events, audit records and logs hold identifiers, sizes and digests, not file contents or prompts.
- Sensitive paths (as defined by the host `SensitivePaths`) are excluded from snapshots and from agent context and are write-denied (THREAT-002).
- The database insert layer rejects payloads containing credential-like keys (AC-018).

## Decisions

- Device-level encryption is provided by Android; the module adds no custom cryptography.

## Rules

1. Do not copy secrets into a workspace; the ignore rules exclude known secret files.
2. Retention: audit and events are kept until the team is deleted unless the user clears them.

## Examples

A `.env` file in the project is excluded from snapshots; an agent therefore never sees it and cannot write it.

## Future Considerations

Optional encrypted export of runs for backup.
