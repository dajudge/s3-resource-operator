# Quality Dashboard

> Automatically generated from [`cc4745c`](https://github.com/dajudge/s3-resource-operator/commit/cc4745cbe388fefd189ce4231a0864328238fd3b) after a successful [`main` CI run](https://github.com/dajudge/s3-resource-operator/actions/runs/33224663273) on 2026-08-29 00:54 UTC. Do not edit this branch manually.

[![Line coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/dajudge/s3-resource-operator/badges/metrics/line-coverage.json)](https://github.com/dajudge/s3-resource-operator/actions/runs/33224663273)
[![Branch coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/dajudge/s3-resource-operator/badges/metrics/branch-coverage.json)](https://github.com/dajudge/s3-resource-operator/actions/runs/33224663273)
[![Reconciler mutation score](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/dajudge/s3-resource-operator/badges/metrics/mutation-score.json)](https://github.com/dajudge/s3-resource-operator/actions/runs/33224663273)

## JUnit

| Metric | Result |
| --- | ---: |
| Tests | **62** |
| Failures | **0** |
| Errors | **0** |
| Skipped | 0 |
| Test runtime | 75.84 s |
| Report files | 14 |

## JaCoCo

| Coverage | Result | Covered |
| --- | ---: | ---: |
| Instruction | **97.8%** | 1982 / 2027 |
| Line | **97.9%** | 415 / 424 |
| Branch | **88.5%** | 161 / 182 |
| Complexity | **88.6%** | 164 / 185 |
| Method | **98.9%** | 93 / 94 |
| Class | **95.2%** | 20 / 21 |

## PIT mutation testing (configured reconciler scope)

The normal PIT profile covers `ResourceValidation`, `ReconcilerSupport`, `S3UserReconciler`, and `S3BucketReconciler`. It is not a project-wide mutation score.

| Metric | Result |
| --- | ---: |
| Reconciler-scope mutation score | **100%** |
| Detected | **121 / 121** |
| Killed | 121 |

## Quality gates

The dashboard is published only after all aggregate CI requirements pass:

| Gate | Result |
| --- | :---: |
| Formatting | ✅ |
| Clean Code / Checkstyle | ✅ |
| JUnit | ✅ |
| PMD / CPD | ✅ |
| SpotBugs | ✅ |
| Runtime license validation | ✅ |
| Helm + cluster E2E | ✅ |
| PIT mutation testing | ✅ |
| Focused event-source PIT | ✅ |
| Native build + ABI smoke test | ✅ |

Full JaCoCo, PIT, and test-report artifacts are attached to the [source CI run](https://github.com/dajudge/s3-resource-operator/actions/runs/33224663273).
