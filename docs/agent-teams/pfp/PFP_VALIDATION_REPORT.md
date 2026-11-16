# PFP Validation Report

| Field | Value |
|---|---|
| Package | Anvex Agent Teams (multi-agent module), package version 1.1.0 |
| Date | 2026-11-12 |
| Validator | PFP Validator 5.7.0 (PFP Generator 5.7.7), jsonschema 4.26.0 |
| Mode | hybrid (analysis of Anvex 4.15.2: 498 files) |
| Verdict | **APPROVED** |

## Validator output (verbatim)

```text
PFP Validator 5.7.0 — ANVEX_AGENTS_PFP
Verdict: APPROVED
  V1: PASS
  V2: PASS
  V3: PASS
  V4: PASS
  V5: PASS
  V6: PASS
  V7: PASS
  V8: PASS
  V9: PASS
Findings: 0 ERROR, 0 WARNING, 3 INFO
  [INFO] V3: control-plane traceability graph validated: schema, unique IDs, typed edges, and requirement coverage
  [INFO] V3: semantic consistency layer: no explicit semantic claims supplied
  [INFO] PRODUCTION: Production Compliance not evaluated; provide --production-compliance after implementation.
```

## Package size

68 markdown documents (about 19119 words); 47 requirements, 34 decisions (29 LOCKED, 2 OPEN, 3 DEFERRED), 23 architecture nodes, 32 tasks, 32 acceptance tests, 12 threats.

## Checks beyond the validator

1. No unresolved tokens and no undefined IDs in any document; no orphan architecture node, decision, task or acceptance test.
2. Version 1.0.0 was checked against the Anvex source (one wrong class name was found and corrected). Version 1.1.0 adds ADR-030 to ADR-034 describing what was built; those facts come from the implementation and its tests.
3. Mutation sanity test of the validator (version 1.0.0): four deliberate defects were each rejected.

## Implementation evidence (not produced by this validator)

The package states what was verified: see the module's `evidence/` directory (agents-core 203 tests, agents-fs 54 tests, 45 hand-written mutations all caught). The Android modules (agents-runtime, agents-ui) were never compiled or run and remain UNVERIFIED.

## Limits

- Tool limitation (LIV-D-002): the validator's security-trace vocabulary is cryptographic; only the credentials requirement is registered there.
- Scope decisions SD-001 to SD-005 have status `assumed` and still need the product owner's confirmation.
- ADR-028 and ADR-029 are OPEN with recommended defaults.
- Production compliance is not evaluated.
