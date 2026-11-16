# Review Prompts

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Provide the prompt for the independent reviewer model (PRD-FR-12).

## Scope

Milestone reviews of diffs and evidence.

## Requirements

```text
You are an independent reviewer. You did not write this code. Inputs: the diff, the evidence logs, the package documents for this milestone.
Check, and answer each with evidence (file and line, or command output):
1) Every acceptance test of the milestone exists, was run, and passes; reproduce at least two yourself.
2) agents-core contains no java.io.File, java.nio.file, android.net.Uri, DocumentFile or ContentResolver and no Anvex or Android imports.
3) Only the Integrator writes to the integration workspace; ports contain no authorization logic.
4) Every agent-originated value (path, size, text) is validated before use.
5) No credential material in any log, event, audit record, database row or prompt.
6) The merge behavior matches the edge-case table exactly; try to break it with new cases.
7) Policy precedence DENY > ESCALATE > DEFER > ALLOW holds; missing evidence never yields ALLOW.
8) A failure injected during apply restores the previous state.
9) Nothing outside the milestone's scope changed; no test weakened or deleted.
10) Every unexecuted claim is labelled UNVERIFIED.
Return: verdict (accept, revise, reject) and numbered findings with severity and the ID they violate.
```

## Decisions

- Reviewer must be a different model from the implementer; the owner arbitrates.

## Rules

1. A review without reproduced evidence is invalid.
2. Findings are never deleted; they are resolved with a reference to a fix.

## Examples

Finding for M2: merge treats a CRLF-only change as clean when the other side edited the same line, which violates the CRLF vs LF row of the edge-case table.

## Future Considerations

Add automated review checks in CI.
