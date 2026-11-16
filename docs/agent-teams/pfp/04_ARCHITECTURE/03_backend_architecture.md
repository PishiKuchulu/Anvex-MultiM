# Backend (Engine) Architecture

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Specify the engine internals: scheduler, workspaces, ChangeSets, Integrator, policy and gates (ARC-CORE-2, ARC-FS-1, ARC-FS-2, ARC-FS-3, ARC-CORE-3, ARC-CORE-4, ARC-SEC-1, ARC-CORE-5).

## Scope

agents-core and agents-fs. Android specifics are in the database, API and deployment documents.

## Requirements

**Path model.** `RelPath` is a validated value class: no `..`, no absolute or drive-letter form, no NUL, control or bidi characters, segment length at most 255, total length at most 4096, separators normalized to `/`. Exact rule (ADR-033): a string is a valid RelPath if and only if the host `PathSanitizer` returns it unchanged and it has at most 4096 characters; a parity test on 180,000 generated inputs proves it (TASK-011). The host sanitizer is not idempotent (it rewrites `./C:/x` to `C:/x`), so RelPath refuses such outputs.

**WorkspaceFileSystem (port, agents-core).**
```kotlin
interface WorkspaceFileSystem {
    val workspaceId: String
    fun exists(path: RelPath): Boolean
    fun readBytes(path: RelPath): ByteArray?
    fun writeBytes(path: RelPath, bytes: ByteArray)   // atomic publish
    fun delete(path: RelPath): Boolean                // files only
    fun list(dir: RelPath?): List<EntryInfo>          // null = root
    fun walk(): List<EntryInfo>                       // files only, sorted, no symlinks
    fun digest(path: RelPath): ContentDigest?
    fun createSnapshot(): SnapshotId
    fun restoreSnapshot(id: SnapshotId)
}
interface WorkspaceProvider {
    fun open(projectId: String): WorkspaceFileSystem
    fun fork(source: WorkspaceFileSystem, base: SnapshotId, label: String): WorkspaceFileSystem
    fun dispose(ws: WorkspaceFileSystem)
}
```
There is no absolute-path member (ADR-004). `RealWorkspaceFileSystem` wraps an injected `ProjectFileSystem`; it is the only implementation used by the engine, and the composition root passes only real-path implementations (ADR-001, ADR-002).

**Snapshots (ADR-005).** Blob = file content stored once under its SHA-256. Manifest = sorted list of (RelPath, blobId, size, kind). SnapshotId = SHA-256 of the canonical manifest bytes. `restoreSnapshot` writes every blob back and deletes files absent from the manifest; each blob is hash-verified when read, and a mismatch aborts the restore with `CorruptBlob`. Not captured: empty directories, symlinks, file mode bits.

**Fork.** `fork(source, base, label)` materializes snapshot `base` into a fresh real directory under the agents root and returns a workspace over it. Fork contents never alias the source.

**ChangeSet.** Derived by comparing the fork with its base manifest: entries `Add(path, bytes)`, `Modify(path, baseDigest, bytes)`, `Delete(path, baseDigest)`. Each entry carries the digest of the base content so staleness is detectable.

**Validation (ADR-008).** `ChangeSetValidator` rejects: invalid `RelPath`; more than 2,000 entries or more than 20 MiB total; a single file over 5 MiB; entries under sensitive prefixes supplied by `SensitivePathPort`; entries outside the task write grant; duplicate paths; any symlink or mode entry.

**Merge (ADR-006).** Line-based three-way merge. Lines are split on `\n` and each line keeps its own terminator (including `\r`), so line-ending changes are ordinary edits and are never normalized.

| Case | Behavior |
|---|---|
| Only one side changed vs base | Take that side |
| Both changed, identical result | Clean (convergent) |
| Hunks on disjoint base regions separated by at least one unchanged line | Clean, hunks combined |
| Hunks overlapping or adjacent | Conflict `OVERLAP`; nothing written |
| Binary (contains NUL, not valid UTF-8, or over 2 MiB) | No line merge: one-sided change taken; both changed and different → Conflict `BINARY` |
| CRLF vs LF | Per-line terminators compared; a whole-file line-ending conversion by one side overlaps any edit by the other → Conflict `OVERLAP`; identical conversion on both sides is clean |
| Delete vs unchanged | Delete applied |
| Delete vs edit | Conflict `DELETE_EDIT` |
| Delete on both sides | Clean |
| Add on both sides, same bytes | Clean |
| Add on both sides, different bytes | Conflict `ADD_ADD` |
| File vs directory at the same path | Conflict `TYPE_CLASH` |
| Rename | Not detected in MVP; seen as delete plus add |
| Symlink or mode change in a ChangeSet | Rejected by validation |
| Stale base (ours changed since fork) | Always goes through merge, never a blind write |

**Integrator (ADR-007).** For each ChangeSet: validate → policy decision for every entry → create a rollback snapshot of the integration workspace → merge per file → if any conflict, write nothing and report all conflicts → otherwise write all files, then record the event. A failure while writing restores the rollback snapshot (AC-014).

**Orchestration and recovery (ADR-030, ADR-031, ADR-032).** The Orchestrator (agents-core, ARC-CORE-9) executes scheduler commands: StartAgent forks the integration workspace from a fresh snapshot; when an agent reports finished it derives and validates the ChangeSet BEFORE the scheduler sees "finished" (unusable output becomes a retryable failure, AC-031); RequestReview picks a reviewer that has the approve grant and is not an author, or none (then only the user can verdict); RequestApply runs the Integrator on the integration workspace. Every event is appended to the store before it changes state; replaying the hash-chained log rebuilds the exact scheduler state. The apply journal (ARC-CORE-10) is written durably before the first workspace write; after a restart `ApplyRecovery` restores the pre-apply snapshot and `reconcile()` requeues every interrupted task (AC-030). Forks are deleted only after the launcher reports that the run has really stopped.

**Policy decisions.** `ALLOW`, `DENY`, `ESCALATE` (needs the user), `DEFER` (evidence missing). Precedence DENY > ESCALATE > DEFER > ALLOW; absent evidence never yields ALLOW (ADR-022).

**Scheduler (ADR-020).** State machine `next(state, event) → (state, commands)`. Admission of a ready task requires: all dependencies Completed; running count below the cap; remaining budget; no overlap between its claimed file set and the claims of running tasks, unless the tasks are explicit competitors in separate forks.

**Gates (PRD-FR-12, PRD-FR-14).** A task requiring review reaches `Completed` only after a verdict recorded by a different agent identity or by the user. Verdicts submitted through agent messages are ignored unless they arrive over the reviewer's own run channel.

## Decisions

- ADR-006 fixes the table above; changes need a decision-log entry and new tests (AC-012).
- ADR-024: competing ChangeSets are never merged with each other.

## Rules

1. The Integrator is the only component that writes to the integration workspace.
2. Ports perform no authorization; PolicyEngine performs all of it (ADR-007).
3. A conflict must leave every file untouched.
4. All limits above are configuration constants with tests at the boundary values.

## Examples

Two agents change `util.kt`: A edits lines 10–12, B edits lines 80–82 → clean merge. If B edits line 13 → adjacent → Conflict `OVERLAP`, nothing written.

## Future Considerations

Rename detection, hunk-level partial acceptance and semantic (AST) merge are possible later without changing the ChangeSet or port contracts.
