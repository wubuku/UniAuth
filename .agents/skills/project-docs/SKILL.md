---
name: project-docs
description: Audit, create, organize, and maintain an AI-agent-friendly documentation system for a software repository. Use when asked to create or improve project documentation, establish a documentation hierarchy, organize existing Markdown files, build docs navigation, document architecture or development workflows, classify drafts and historical material, or validate documentation links and coverage. Preserve existing document locations and content by default; create navigation documents that link to existing sources instead of moving or duplicating them.
---

# Project Documentation System

Build a repository-local documentation system that helps humans and AI agents answer:

1. What is this project?
2. Where is the authoritative information?
3. What must be read before changing a subsystem?
4. How is a change verified safely?

Treat navigation, provenance, lifecycle, and maintenance rules as part of the deliverable.

## Package Self-Containment

Treat this skill directory as a portable, self-contained package.

- Use only instructions, references, and scripts stored inside this skill package.
- Do not depend on the original repository from which this skill was copied.
- Do not link to undocumented machine-local paths.
- Read `references/templates.md` when creating a new document scaffold.
- Read `references/checklist.md` before declaring documentation work complete.
- Run `scripts/check_relative_links.py` after adding or changing documentation links.
- Add any future required reference or script to this package and use relative links from this file.

## Core Principles

1. **Repository is the source of truth.** Verify claims against code, configuration, scripts, and tests.
2. **Navigation is more valuable than duplication.** Prefer hubs and indexes that lead to authoritative documents.
3. **Write for change safety.** Document ownership boundaries, high-risk operations, invariants, and verification.
4. **Use progressive disclosure.** Organize information as Hub -> Guide -> Reference.
5. **Preserve provenance.** Existing document locations reveal project history and team workflow.
6. **State uncertainty.** Mark unverified, stale, conflicting, draft, or historical material explicitly.

## Non-Negotiable Rule: Do Not Move Existing Documents

Preserve every existing document at its current path unless the user explicitly requests a move or rename.

| Situation | Required action |
|-----------|-----------------|
| Existing document is useful | Link to it from the new navigation system |
| Existing document is stale | Add a status note or update it incrementally at the same path |
| Existing document is historical | Label it historical in an index; keep it in place |
| Existing document overlaps a new guide | Make the new guide a concise hub and link to the existing detail |
| Existing filename is unclear | Describe it clearly in the index; do not rename by default |
| Existing document conflicts with code | Record the conflict and prefer verified code facts |
| A same-name document already exists | Do not overwrite it; adapt it incrementally or choose a distinct new hub name |

Do not:

- Move existing files into a new directory merely to make the tree look cleaner.
- Copy large sections from existing documents into new documents.
- Replace a detailed historical document with a short normalized version.
- Delete planning, research, or integration documents because they are outdated.
- Break existing links to impose a new naming convention.

When explicit relocation is approved:

1. Preserve Git history where possible.
2. Update every repository-relative link.
3. Add provenance notes at the destination.
4. Validate links before completion.

## Documentation Architecture

Adapt to the repository instead of forcing an empty-template structure.

### Hub Layer

- Root `README.md`: human landing page and quick start.
- Root `AGENTS.md`: durable agent rules, safety boundaries, commands, and documentation links.
- `docs/README.md` or existing `docs/index.md`: canonical documentation navigation.

### Guide Layer

Create only guides justified by the project:

- Architecture and subsystem boundaries.
- Development setup and daily workflow.
- Verification and test harness.
- Configuration and environment behavior.
- API and integration guidance.
- Operations and troubleshooting.
- Contribution and release workflow.

### Reference Layer

Link to detailed existing material:

- Design plans and research.
- Frontend/backend contracts.
- Provider-specific integration notes.
- Database schemas and migration notes.
- Component READMEs.
- Historical progress records and verification reports.

Do not duplicate reference-layer detail in hub documents.

## Lifecycle Classification

Classify documents in indexes without relocating them.

| State | Meaning | Maintenance rule |
|-------|---------|------------------|
| **Live** | Current operational or architectural guidance | Update with relevant code changes |
| **Draft** | Active plan, proposal, or investigation | Keep status and next decision clear |
| **Reference** | Detailed stable material used as needed | Update when the represented contract changes |
| **Historical** | Records a past implementation or decision | Preserve; add warnings rather than rewriting history |
| **Needs verification** | May conflict with current code | Do not rely on it until checked |

Prefer status columns and annotations in navigation documents over directory churn.

## Workflow

Follow the steps in order.

### Step 1: Read Repository Instructions

Read repository-level and applicable nested agent instructions before modifying documents.
Identify generated files, destructive commands, secret locations, and documentation-specific rules.

### Step 2: Audit Existing Documentation

Inventory Markdown without traversing generated dependencies:

```bash
find . -type f -name '*.md' \
  -not -path './.git/*' \
  -not -path '*/node_modules/*' \
  -not -path '*/target/*' \
  -not -path '*/build/*' \
  -not -path '*/dist/*' \
  | sort
```

For each document, capture:

- Path and title.
- Primary topic.
- Intended audience.
- Lifecycle state.
- Whether code confirms its main claims.
- Existing inbound/outbound links.
- Recommended role in the new system.

Use headings and targeted reads before loading every long document in full.

### Step 3: Identify Canonical Sources

Determine which artifacts are authoritative for:

- Runtime topology and module ownership.
- Build, test, lint, and run commands.
- Ports, profiles, databases, and external services.
- Security and authentication behavior.
- API contracts and integration paths.
- Known hazards and unsupported workflows.

Prefer executable configuration and current code over prose. Record disagreements instead of silently choosing a convenient document.

### Step 4: Create a Documentation Plan

Create or incrementally update `docs/drafts/DOCUMENTATION_PLAN.md`.

Include:

- Current-state inventory summary.
- Documents that will remain in place.
- Missing hubs and guides.
- P0/P1/P2 priorities.
- Source documents each new guide will link to.
- Validation plan.
- Deferred work and unresolved conflicts.

Do not use the plan as an excuse to postpone small, clearly safe P0 improvements.

### Step 5: Establish Navigation First

Prefer this order:

1. Create or update `docs/README.md` as the canonical document hub.
2. Create or update `docs/drafts/README.md` to index existing drafts in place.
3. Create `docs/archive/README.md` only if an archive already exists or historical indexing is useful.
4. Add concise documentation links to root `README.md`.
5. Add the documentation hub and critical guides to root `AGENTS.md`.

If an equivalent hub already exists, improve it instead of creating a competing index.

Every hub should:

- Explain its scope.
- Link with repository-relative paths.
- Label lifecycle and reliability.
- Direct readers to the smallest relevant document.
- Avoid repeating detailed content.

### Step 6: Add Missing Guides

Create a guide only when it resolves a real navigation or knowledge gap.

Before writing:

1. Search existing documents for the topic.
2. Select the existing source documents to link.
3. Verify important claims in code/configuration.
4. Read the relevant scaffold in `references/templates.md`.

In the new guide:

- Summarize the current verified model.
- Link to existing detailed documents.
- Identify historical or conflicting material.
- State update triggers.
- Avoid presenting untested commands as verified.

### Step 7: Update Existing Documents Incrementally

Make focused edits when an existing document is the correct source of truth.

Allowed examples:

- Add a status banner.
- Correct a verified stale command.
- Add links to the documentation hub.
- Clarify ownership or update rules.
- Add a short "related documents" section.

Avoid comprehensive rewrites unless requested.

### Step 8: Validate

Read `references/checklist.md`, then verify:

```bash
python3 .agents/skills/project-docs/scripts/check_relative_links.py \
  README.md AGENTS.md docs
```

Also check:

- New Markdown files are discoverable from a hub.
- Links point to files that exist.
- No existing document was unintentionally moved, renamed, or deleted.
- Commands labeled verified were actually run.
- Generated, secret, database, and dependency files were not added.
- `git diff --check` passes.

## Link And Content Rules

- Use relative repository links, never machine-local absolute paths.
- Link to exact documents rather than bare directories when a clear entry document exists.
- Use descriptive link labels.
- Keep copied excerpts short and identify the source document.
- Avoid links into ignored build output.
- Do not claim a document is current solely because it has a recent filename.
- Do not invent project status, ownership, SLAs, support guarantees, or production readiness.
- Use exact dates when distinguishing historical and current states.

## Documentation Plan Priorities

Use these priorities as guidance, not mandatory file generation.

| Priority | Purpose |
|----------|---------|
| **P0** | Navigation and safety: README, AGENTS, docs hub, destructive-operation warnings |
| **P1** | Architecture, development, configuration, verification, primary contracts |
| **P2** | API detail, integrations, troubleshooting, contribution workflow |
| **P3** | Historical classification, source-material indexes, optional templates |

## Completion Report

Report:

1. Skill or documentation files created.
2. Existing files updated.
3. Existing files deliberately left in place.
4. Validation commands and results.
5. Known conflicts, stale material, and deferred documentation.

Do not report the documentation system as complete when core links are broken or key claims remain unverified.
