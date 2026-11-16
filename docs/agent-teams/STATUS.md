# Anvex Agent Teams module: status at hand-over

Built against the Anvex 4.15.2 source and the PFP package `ANVEX_AGENTS_PFP` (hybrid mode). Nothing in the main Anvex source was modified.

## Layout

| Path | What | Verification |
|---|---|---|
| `feature/agents-core` | pure Kotlin (stdlib only): domain, deterministic scheduler, policy engine, change sets, three-way merge, integrator, orchestrator, event codec and log, journal and recovery, reviewer-verdict and plan parsers, strict JSON, prompts, line diff | **203 JUnit tests pass** (`evidence/agents-core.log`) |
| `feature/agents-fs` | `RealWorkspaceFileSystem` over the host `ProjectFileSystem`, content-addressed snapshots, fork/dispose | **54 JUnit tests pass** on a real file system with the real `RealProjectFileSystem` (`evidence/agents-fs.log`) |
| `feature/agents-runtime` | Room `agents.db`, agent runner, reviewer runner, coroutine launcher, foreground service, graph | **UNVERIFIED: never compiled or run** |
| `feature/agents-ui` | Compose screens, ViewModel, strings (en, fa) | **UNVERIFIED: never compiled or run** |
| `integration/` | host binding template and the integration contract (not applied) | UNVERIFIED |
| `evidence/` | test logs and the mutation table | |
| `engine-snapshot/` | three Anvex source files copied only so agents-fs compiles without Gradle | |

## What the evidence shows

- Parallel agents in isolated forks, conflict-aware scheduling, independent review gates, validation of every agent output, conservative merge, atomic apply with rollback, budgets, and crash recovery are exercised by tests, including a 180,000-event randomized run of the scheduler (invariants checked after every event), a crash injected at every step of an apply, and symlink and corruption attacks on a real file system.
- 45 hand-written mutations (each breaks one safety rule) are all caught: `evidence/mutation-results.txt`. Two of them survived at first and exposed real test gaps (an approve-capable author masking the independence rule; a redundant gate check), which were then closed.

## What was NOT verified

- Everything in `agents-runtime` and `agents-ui`. Only a syntax-level check was possible because Gradle, Room, coroutines and Compose were unavailable. Expect compile fixes.
- No real model provider was called. The runner and reviewer have never talked to a model.
- Nothing was run on an Android device (service lifetime, process death, RTL layout, memory, battery).
- The merge, scheduling and recovery logic is proven only on the JVM test environment (kotlinc 2.0.21, JDK 21, `-jvm-target 17`), not through the Gradle build.

## Reproduce the JVM verification

```
KOTLINC_HOME=/path/to/kotlinc-2.0.21 JUNIT_JAR=/path/junit-4.13.2.jar HAMCREST_JAR=/path/hamcrest.jar bash build.sh core
... bash build.sh fs      (mutation check: python3 mutate2.py 0 45)
```

## Findings about the host (Anvex 4.15.2)

- `DurableTaskEngine` runs tasks strictly one at a time; `GitLite` has no merge or worktree; both were therefore not used.
- `PathSanitizer` is not idempotent (`./C:/x` becomes `C:/x`, which it would then reject). `RelPath` accepts only strings the sanitizer returns unchanged.

## Decisions that need the product owner

- Scope decisions SD-001 (PFP is a development-time standard, not a runtime), SD-003 (manager only proposes plans), SD-004 (profile import/export deferred): proposed, status `assumed`.
- OPEN: default concurrency cap (recommended 3) and default review policy (recommended: independent reviewer always required).

## Known gaps

See `integration/INTEGRATION_CONTRACT.md`: no shell execution for agents, SAF projects refused, task drafts not persisted, no startup garbage collection of leftover forks, reviewer outside the concurrency cap, no manager runner or UI (only the validated plan parser).

## Next steps

1. Add the four modules to a copy of Anvex, build with Gradle, fix compile errors, commit the Room schema snapshot.
2. Bind `AnvexAgentsHostImpl`, then run the device checklist in the integration contract.
3. Have a different model or person review the integrator, merge, policy, validator and CAS code (PFP review process).
4. Only then execute the integration contract (milestone M6).
