# Frontend Architecture

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Describe how the Compose UI is structured and how it stays decoupled from the engine (ARC-UI-1).

## Scope

agents-ui only. Excludes the existing harness web UI.

## Requirements

- Pattern: unidirectional data flow. `AgentTeamsViewModel` exposes `StateFlow<UiState>`; the UI sends `UiIntent` values.
- ViewModels depend on a `TeamEngineFacade` interface defined in agents-runtime, never on agents-core classes directly.
- UI models are immutable data classes; mapping from domain to UI lives in the ViewModel layer.
- Navigation: one route added to the host graph; internal navigation uses a nested graph (ADR-023).
- Localization: `values/strings_agents.xml` and `values-fa/strings_agents.xml`; no hard-coded text (TASK-028).
- Compose BOM 2024.12.01, Material 3 (ADR-011).

## Decisions

- The UI layer is the last milestone to start real work (RDM-M-4); earlier it may use previews with fake data.

## Rules

1. No business rule in a composable.
2. State survives rotation and process death through saved state plus the persisted run state (PRD-FR-22).

## Examples

`RunMonitorScreen(state: RunMonitorUiState, onIntent: (UiIntent) -> Unit)` renders agent cards from `state.agents`.

## Future Considerations

A tablet two-pane layout and a project activity timeline can be added without changing ViewModels.
