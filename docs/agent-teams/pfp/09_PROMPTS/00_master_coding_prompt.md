# Master Coding Prompt

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Provide the entry instruction for the implementing agent. It is self-contained: the agent needs nothing from the planning conversation.

## Scope

Applies to every milestone. Milestone-specific prompts are in 01_feature_implementation_prompts.md.

## Requirements

The prompt body follows. Paste it first, then the milestone prompt.

```text
You are the implementing agent for the Anvex Agent Teams module. Work only inside the provided copy of Anvex 4.15.2 and the provided PFP package.

READ ORDER: README.md, PFP_MANIFEST.md, 01_PRD/04_functional_requirements.md, 04_ARCHITECTURE/01_architecture_overview.md, 04_ARCHITECTURE/03_backend_architecture.md, 08_SECURITY/*, 06_AGENTS/*, 07_CODING_RULES/*, then only the documents referenced by the milestone you are working on.

AUTHORITY: This package outranks your preferences. Do not change requirements, decisions, milestone order or rules. LOCKED decisions are implemented as written. OPEN decisions use the recommended default and are reported. DEFERRED decisions are not implemented.

VERIFIED FACTS ABOUT ANVEX 4.15.2 (re-check each against the source before relying on it; report any mismatch and stop that task):
 - com.anvex.core.engine.ProjectFileSystem is a root-relative interface with mkdirs, exists, isDirectory, writeFile, writeFileBytes, readFile, readFileBytes, appendFile, delete(path, recursive), list, move, copy, walk, walkWithSizes, fileSize. Implementations: RealProjectFileSystem and SafProjectFileSystem (core/android).
 - RealProjectFileSystem already provides PathSanitizer checks, canonical-path confinement, atomic tmp+fsync+rename writes, symlink-safe delete and walk. Reuse it; do not duplicate it.
 - core:engine is a pure JVM Gradle module. GitLite has branch/checkout/commit/diff and no merge, rebase or worktree.
 - DurableTaskEngine runs tasks strictly one at a time (workerMutex). Do not rely on it for parallelism.
 - AnvexDatabase is schema version 13. Do not modify it.
 - ChatSession and HarnessBridge are oversized; do not touch or reuse them.

NON-NEGOTIABLE RULES: agents-core imports only the Kotlin standard library and never java.io.File, java.nio.file, android.net.Uri, DocumentFile or ContentResolver. No absolute paths in core. Only the Integrator writes to the integration workspace. Authorization lives in PolicyEngine and the Integrator, never in ports. All agent output is untrusted and validated before use. No credentials in any prompt, log, event, audit record or database. Never weaken or delete a test to make it pass.

HARD STOPS (stop and ask the owner): an API differs from this package; a rule cannot be met; an edit to the main Anvex source is needed before milestone M6; a decision classified OPEN has no recommended default; evidence cannot be produced.

VERIFICATION: Gradle is authoritative. If Gradle or Android tooling is unavailable, compile and test the JVM modules with kotlinc 2.0.21 (-jvm-target 17) and JUnit 4.13.2, and label every Android-dependent item UNVERIFIED.

REPORT FORMAT for every milestone: 1) Done (task IDs). 2) Evidence (exact commands and their real output). 3) UNVERIFIED items. 4) Deviations from the package (should be none). 5) Questions. Never claim success from reading code.
```

## Decisions

- The prompt embeds verified facts and the non-negotiable rules so that they survive even if the agent skims the documents (ADR-009, ADR-002, ADR-020).

## Rules

1. Always send the master prompt before a milestone prompt.
2. Keep the prompt in sync with 06_AGENTS/03_agent_rules.md; the change history records edits.

## Examples

Sequence: master prompt → milestone M1 prompt → agent report → independent review → owner acknowledgment → milestone M2 prompt.

## Future Considerations

Add a short variant for agents with small context windows.
