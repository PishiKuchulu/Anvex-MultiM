# Folder Creation Order

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Specify where files go and in which order they are created.

## Scope

The module's source tree inside the Anvex copy.

## Requirements

```text
feature/agents-core/src/main/kotlin/com/anvex/agents/core/{domain,scheduler,policy,changeset,merge,integrator,ports,budget,events}
feature/agents-core/src/test/kotlin/com/anvex/agents/core/...
feature/agents-fs/src/main/kotlin/com/anvex/agents/fs/{RealWorkspaceFileSystem,Cas,Fork}.kt
feature/agents-runtime/src/main/kotlin/com/anvex/agents/runtime/{db,runner,host,adapters}
feature/agents-ui/src/main/kotlin/com/anvex/agents/ui/...
evidence/M0 ... evidence/M6
```
Order: agents-core skeleton → ports → fakes → tests → implementations → agents-fs → runtime → ui.

## Decisions

- The directory `feature/` is new; no existing directory is moved.

## Rules

1. Create tests before implementations for scheduler, merge and policy.
2. Evidence folders are committed with each milestone.

## Examples

M1 creates `feature/agents-core` first and compiles the empty module before adding logic.

## Future Considerations

Add `feature/agents-cli` if a desktop runner is built.
