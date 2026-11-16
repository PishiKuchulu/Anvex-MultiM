# Future Platforms

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Record how the pure-JVM core can run beyond the Android app.

## Scope

Platform ideas; none is in scope now.

## Requirements

- JVM desktop or CLI: agents-core and agents-fs run unchanged; only runtime and UI would be replaced.
- Remote workers: a remote AgentRunnerPort implementation could run agents elsewhere while the core stays on the device.
- Cross-device teams: the event log can be the synchronization unit.

## Decisions

- No platform other than Android is committed; the port boundary keeps options open (ADR-009).

## Rules

1. Do not add platform specific code to agents-core for any future platform.
2. Evaluate each platform with a new package.

## Examples

A CLI wrapper runs the scheduler against a local directory for regression testing.

## Future Considerations

Kotlin Multiplatform for agents-core if a non-JVM target appears.
