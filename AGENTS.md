# AGENTS.md

Guidance for coding agents working in this repository.

## Pull requests

- Keep PRs focused, independent, and as small as practical.
- Prefer multiple independent PRs over one large mixed change.
- Use squash merges.
- If a PR is green, review-clean, mergeable, and independent, merge it promptly.
- Do not delay a merge merely because another PR is expected to land first. Refresh/rebase only when there is meaningful file overlap, dependency coupling, semantic interaction, or an actual stale-base risk.
- If overlapping changes landed on `main`, refresh the branch before merging and verify that no semantic changes were lost.
- Do not preserve stale work just because a PR already exists. If a draft/refactor is superseded or increases complexity, close it with a short explanation.

## Reviews

- Always inspect PR review comments and review threads before merging.
- Respond to actionable review feedback.
- Resolve a review thread only after the feedback is actually addressed or clearly obsolete and explained.
- Treat unresolved P1/P2 review findings as merge blockers unless they are demonstrably obsolete.

## CI

- Required checks on `main` include `verify`, `analyze`, and `CodeQL`.
- If CI is red because of the PR, fix it before merging.
- A PR is considered green only when its relevant focused jobs and aggregate required checks have passed.
- Avoid creating no-op or artificial commits solely to work around CI unless a fresh user-authored commit is genuinely required to trigger checks.

## Clean Code

- Clean Code is mandatory for production and test code; prefer simple, readable code over clever abstractions.
- Keep files and methods focused. Checkstyle enforces a maximum Java file length of 600 lines and method length of 80 non-empty lines.
- Keep control flow simple. Checkstyle enforces cyclomatic complexity of at most 10 per method.
- Do not suppress or weaken Clean Code checks to make a change pass. Refactor the code instead unless an explicit exception is approved.
- Treat these limits as ceilings, not targets; prefer substantially smaller files, methods, and complexity where practical.

## Mutation testing

- Do not weaken PIT quality gates.
- Keep the mutation threshold at 95% and coverage threshold at 90% unless explicitly instructed otherwise.
- When expanding PIT target classes, add focused tests that exercise the newly included production code rather than lowering thresholds.

## Change discipline

- Preserve existing behavior unless the PR explicitly intends to change it.
- Avoid unrelated cleanup in feature/test PRs.
- Do not perform the previously proposed Maven profile cleanup unless explicitly requested.
- Maven build output under `/target/` is generated content and must not be committed.

## Working style

- Act proactively. Do not ask for clarification when the intended action is clear from repository state and these guidelines.
- Prefer concrete progress over status-only commentary: inspect, fix, respond to reviews, resolve addressed threads, and merge when safe.
- Keep status updates concise and mention blockers only when they materially affect progress.
