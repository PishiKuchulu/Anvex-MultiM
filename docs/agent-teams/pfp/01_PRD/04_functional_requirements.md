# Functional Requirements

> Package: Anvex Agent Teams (multi-agent module) · PFP 5.7.0 · 2026-11-05

## Purpose

The single registry of functional requirements. Every row is a control-plane requirement with a stable ID and an exact source text preserved in input.txt.

## Scope

All requirements originate from the product owner's specification (ANVEX — Advanced Multi-Agent Development System) and the directives given during planning. Dispositioned rows are explained by scope decisions in PFP_CONTROL_PLANE.json.

## Requirements

| ID | Requirement | Priority |
|---|---|---|
| PRD-FR-1 | Evolve Anvex into a multi-agent development environment | must |
| PRD-FR-2 | Multiple agents collaborate on one project with different models/providers/roles | must |
| PRD-FR-3 | Per-agent provider and model | must |
| PRD-FR-4 | Per-agent model parameters | should |
| PRD-FR-5 | Same task to multiple agents | must |
| PRD-FR-6 | Simultaneous agent execution | must |
| PRD-FR-7 | Task dependencies and execution order | must |
| PRD-FR-8 | Compare independent implementations | must |
| PRD-FR-9 | Approve, reject, modify, reassign tasks | must |
| PRD-FR-10 | Pause, resume, terminate agents | must |
| PRD-FR-11 | Per-agent read/modify/execute/approve permissions | must |
| PRD-FR-12 | Agents cannot self-verify | must |
| PRD-FR-13 | Never silently overwrite another agent's changes | must |
| PRD-FR-14 | Gates cannot be bypassed via manager approval | must |
| PRD-FR-15 | Parallelism from dependencies and file-level conflicts | must |
| PRD-FR-16 | Isolated agent workspaces | must |
| PRD-FR-17 | Merge and conflict resolution | must |
| PRD-FR-18 | Rollback and recovery | must |
| PRD-FR-19 | Per-agent token tracking | must |
| PRD-FR-20 | Configurable execution budgets | must |
| PRD-FR-21 | Maximum concurrent agent limit | must |
| PRD-FR-22 | Interrupted execution recovery | must |
| PRD-FR-23 | Agent output is untrusted until validated | must |
| PRD-FR-24 | Secure credential handling | must |
| PRD-FR-25 | Governance independent of provider/model | must |
| PRD-FR-26 | Controlled context distribution | should |
| PRD-FR-27 | New agent types without core changes | must |
| PRD-FR-28 | Architecture allows section-13 capabilities later | should |
| PRD-FR-29 | Compatible with existing Anvex architecture | must |
| PRD-FR-30 | No unnecessary external infrastructure | must |
| PRD-FR-31 | PFP remains the authoritative governance layer | must |
| PRD-FR-32 | Git branches and worktrees where supported | should |
| PRD-FR-33 | Central Project Manager agent | should |
| PRD-FR-34 | Import/export agent configurations | could |
| PRD-FR-35 | Engine works only on real file paths | must |
| PRD-FR-36 | No direct SAF in the engine core | must |
| PRD-FR-37 | SAF only at an import/export gateway | should |
| PRD-FR-38 | Project must be real-FS before entering the workspace | must |
| PRD-FR-39 | Port named WorkspaceFileSystem / RealWorkspaceFileSystem | must |
| PRD-FR-40 | No getAbsolutePath; root-relative path model; separate ports | must |
| PRD-FR-41 | WorkspaceFileSystem delegates to the existing ProjectFileSystem | must |
| PRD-FR-42 | Snapshots backed by recoverable content | must |
| PRD-FR-43 | MVP merge: auto only for non-overlapping regions | must |
| PRD-FR-44 | Defined edge-case behavior | must |
| PRD-FR-45 | Ownership and permissions enforced in core/Integrator | must |
| PRD-FR-46 | Validate every agent output before applying | must |
| PRD-FR-47 | Build the module completely, integrate at the end | must |

Dispositions: PRD-FR-31 (SD-001), PRD-FR-32 (SD-002), PRD-FR-33 (SD-003), PRD-FR-34 (SD-004), PRD-FR-37 (SD-005).

## Decisions

- SD-001: PFP is a development-time standard (specification plus Python validators), not a runtime, so the in-app Policy Engine implements its Runtime Enforcement Contract semantics (ADR-022).
- SD-002: Git worktrees are not available because GitLite has one working tree and no merge; isolation uses CAS-backed workspace forks (ADR-005).
- SD-003: the manager only proposes plans until proven (ADR-025).
- SD-004: profile import/export is deferred (ADR-026).
- SD-005: the SAF gateway is deferred (ADR-027).

## Rules

1. No requirement may be removed or reworded without a scope decision (V6).
2. Persian directive texts are stored with markdown markers stripped; the meaning is unchanged.

## Examples

PRD-FR-14 means that a message such as "manager approves" from any agent leaves the task in `UnderReview` until the independent reviewer or the user decides.

## Future Considerations

Section 13 capabilities of the original specification are covered only as architectural headroom by PRD-FR-28.
