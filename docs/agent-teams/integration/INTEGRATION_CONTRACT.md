# Integration contract: adding Agent Teams to Anvex 4.15.2

Status: **not yet executed**. Milestone M6 of the package. ADR-023 allows exactly these changes to the main source; anything else needs a new ADR.

| # | File in the main source | Change |
|---|---|---|
| 1 | `settings.gradle.kts` | `include(":feature:agents-core", ":feature:agents-fs", ":feature:agents-runtime", ":feature:agents-ui")` and copy the `feature/` directory |
| 2 | `app/build.gradle.kts` | `implementation(project(":feature:agents-ui"))` |
| 3 | `AppGraph` | create one `AgentsGraph(context, AnvexAgentsHostImpl(...))` lazily; bind the lambdas as documented in `AnvexAgentsHostImpl.kt` |
| 4 | navigation graph + project screen | one route and one entry button "Agent Teams" that call `AgentTeamsRoute(graph, projectId, onClose)` |
| 5 | `res/values/strings.xml` handled by the module | nothing: the module ships `strings_agents.xml` (en) and `values-fa/strings_agents.xml` |
| 6 | manifest | nothing expected: the library manifest declares `AgentTeamsService`; the app already declares `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`. Re-check at integration |
| 7 | `tools/build_source_mirror.py` | regenerate the bundled source mirror |

Room: generate `feature/agents-runtime/schemas/.../1.json` with the first build and commit it. `anvex.db` (schema 13) is not touched.

## Verification before the integration commit is accepted

1. `git diff --stat` shows only the seven areas above plus the new `feature/` directory (acceptance `main_surface`).
2. Gradle: `:feature:agents-core:test`, `:feature:agents-fs:testDebugUnitTest`, `:feature:agents-runtime:testDebugUnitTest`, `:app:assembleDebug`.
3. Anvex scripts: `tools/final_release_audit.py`, `tools/verify_source_mirror.py`, `tools/verify_room_schema_snapshots.py`.
4. Device checklist (the Android layer was never run): start a team on a real project, run two agents in parallel, force a conflict, approve an escalation, kill the app during a run and reopen it, rotate the screen, switch to Persian and check RTL, run with the screen off for five minutes, SAF-backed project is refused with the message.
5. Re-verify the host facts used by the adapters (they were read from the 4.15.2 sources): `ProviderClient.streamChat`, `ChatRequest`, `StreamEvent`, `BoltStreamParser.parse`, `ParserCallbacks.onActionClose`, `ActionDispatcher(fs)` and `applyFileAction`, `SystemPromptFactory.buildSystemPrompt`, `RealProjectFileSystem(File)`.

## Known gaps in this build (not hidden)

- Agents cannot run shell commands (`ShellPort`/`VersionControlPort` have no implementation yet); file actions only.
- SAF-backed projects are refused (ADR-027, deferred).
- Task drafts of a team are kept in memory, not persisted.
- Orphan fork directories and CAS blobs left by a killed process are not garbage-collected on startup yet (`CasStore.gc()` exists and is tested; calling it at startup is not wired).
- The reviewer is not counted against the concurrency cap.
- Autonomous manager (milestone M5) is implemented as the pure plan parser/validator only (`PlanParser`, `PlanProposal`); no manager agent runner or UI exists.
