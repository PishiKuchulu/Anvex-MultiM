# Deployment Strategy

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Describe how the module is built, versioned, integrated and released (ARC-INT-1, ADR-023).

## Scope

Build and integration only. No server deployment exists.

## Requirements

- **Build baseline:** a copy of Anvex 4.15.2 with the four modules added; versions come from the existing catalog (ADR-013).
- **Integration contract (final step, milestone RDM-M-6):** (1) `settings.gradle.kts` include lines; (2) dependency lines in `app/build.gradle.kts`; (3) wiring in `AppGraph` using the adapters; (4) one navigation route and entry button; (5) string resources in `values` and `values-fa`; (6) declare the module's service with `foregroundServiceType="dataSync"` (the app manifest already declares FOREGROUND_SERVICE and FOREGROUND_SERVICE_DATA_SYNC; re-check at integration); (7) regenerate the bundled source mirror with `tools/build_source_mirror.py`.
- **Verification before release:** Anvex `final_release_audit.py`, `verify_source_mirror.py`, `verify_room_schema_snapshots.py`, the module's tests, and the manual device checklist (AC-027, AC-028).
- **Versioning:** the module has its own semantic version recorded in the Anvex CHANGELOG at integration.

## Decisions

- No new repository or signing configuration is introduced.

## Rules

1. The integration commit contains only contract changes plus the module sources.
2. If any audit script fails, integration stops and the cause is recorded.

## Examples

Integration diff reviewed with `git diff --stat`: only the seven contract areas appear (AC-028).

## Future Considerations

Publishing the module as a standalone library is possible because it has no host source dependencies other than the adapters.
