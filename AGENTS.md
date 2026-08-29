# AGENTS.md

Guidance for coding agents working in this repository.

## Pull requests

- Keep PRs focused, independent, and as small as practical.
- Prefer the smallest cohesive PR that makes material progress over the smallest possible PR. Do not fragment one responsibility into a stream of micro-PRs when a slightly broader change is easier to review and meaningfully reduces maintenance cost.
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
- Temporary repair/export workflows are allowed when the normal toolchain cannot conveniently apply a generated fix from the agent environment, but keep them one-shot and branch-scoped.
- When practical, make a temporary workflow apply its generated fix itself, commit the result back to the same PR branch, and delete its own workflow file in that same commit or run. Avoid leaving an artifact-download/manual-copy cleanup step for the next agent turn.
- A temporary self-mutating workflow must be constrained to trusted same-repository branches, use only the minimum required write permissions, and avoid recursive reruns after it removes itself.

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

## Improvement ROI

- Improvement-only work must have a concrete maintenance payoff beyond aesthetic cleanliness. Good reasons include removing meaningful duplication, reducing mixed responsibilities or cognitive load, exposing or closing a real testing gap, eliminating a recurring CI/review failure mode, or making a known future change materially cheaper.
- Stop general cleanup when the remaining proposals are mostly movement, renaming, abstraction for its own sake, marginal metric gains, or tests whose maintenance cost exceeds the behavior they protect.
- Do not tighten quality thresholds merely because the current code happens to fit under stricter numbers. Increase a gate only when it addresses an observed maintenance or defect risk.
- As the codebase becomes cleaner, switch from continuous beautification to opportunistic refactoring: improve an area when a feature, bug, or clearly identified maintenance pain takes work there.
- Before starting another improvement PR, identify the concrete maintenance pain it removes. If that cannot be stated clearly, prefer stopping the cleanup campaign.

## Change discipline

- Preserve existing behavior unless the PR explicitly intends to change it.
- Avoid unrelated cleanup in feature/test PRs.
- Do not perform the previously proposed Maven profile cleanup unless explicitly requested.
- Maven build output under `/target/` is generated content and must not be committed.

## Working style

- On every fresh session or handoff, read this `AGENTS.md` first, then inspect all open PRs, current CI status, review comments, and unresolved review threads before starting new work. Prioritize fixing red PRs and actionable review feedback over opening or advancing another PR.
- Act proactively. Do not ask for clarification when the intended action is clear from repository state and these guidelines.
- Prefer concrete progress over status-only commentary: inspect, fix, respond to reviews, resolve addressed threads, and merge when safe.
- Keep status updates concise and mention blockers only when they materially affect progress.
