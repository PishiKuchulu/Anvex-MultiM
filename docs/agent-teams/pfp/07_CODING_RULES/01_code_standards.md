# Code Standards

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Fix the code style and safety standards of the module, aligned with 04_ARCHITECTURE.

## Scope

All Kotlin sources of the four modules.

## Requirements

- Kotlin 2.0.21, JVM target 17, explicit API mode off but public API documented with KDoc.
- Immutable data classes and `val` by default; no mutable state shared across threads without an owner.
- No `!!`; no `runBlocking` anywhere in the module (host code has it as known debt); no global mutable singletons.
- Errors: sealed result types for expected failures; exceptions only for programmer errors.
- Time and randomness only through `ClockPort` and `IdPort`.
- No logging of file contents or prompts; log IDs and sizes.
- All limits are named constants in one `Limits` object.

## Decisions

- Style follows the host's existing Kotlin style; no formatter change is introduced.

## Rules

1. Public functions in agents-core are pure unless they take a port.
2. Every `when` over a sealed type is exhaustive without `else`.

## Examples

`sealed interface ApplyResult { data class Applied(...) ; data class Conflicts(val items: List<Conflict>) ; data class Rejected(val reasons: List<String>) }`

## Future Considerations

Enable explicit API mode if the module is published separately.
