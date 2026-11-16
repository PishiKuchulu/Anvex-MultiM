# Authentication of Provider Access

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

Define how access to model providers is handled, since provider credentials are the only secrets the module touches (PRD-FR-24).

## Scope

Provider credentials and the identity of agents inside the engine. User sign-in to Anvex is out of scope.

## Requirements

- The module holds a **provider reference** (the Anvex provider ID), never a key (ARC-SEC-2).
- At run time the host adapter returns a `ProviderClient` already configured with the credential; the module never reads the credential value.
- Stages: credential selection is a user action in provider settings; use of the credential happens inside the host client; the stored credential is protected by the host's secure storage (encryption/decryption stage, key wrapping by Android Keystore as implemented by the host).
- Agent identity inside the engine is an internal ID assigned at creation; it is not a security principal outside the app.
- Different agents may reference different providers; no credential is shared through the module.

## Decisions

- Credential custody remains entirely with the host (ADR-018).

## Rules

1. Never include a credential in a prompt, log, event, audit record, exported file or error message.
2. Error messages from providers are scrubbed before storage.

## Examples

The agent profile JSON stores `providerRef: "prov_8f2"`; a database scan finds no key material (AC-018).

## Future Considerations

Per-agent provider quotas could be added through the usage port.
