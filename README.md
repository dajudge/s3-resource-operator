# s3-resource-operator

[![CI](https://github.com/dajudge/s3-resource-operator/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/dajudge/s3-resource-operator/actions/workflows/ci.yml)
[![CodeQL](https://github.com/dajudge/s3-resource-operator/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/dajudge/s3-resource-operator/actions/workflows/codeql.yml)
[![Published release E2E](https://github.com/dajudge/s3-resource-operator/actions/workflows/published-release-e2e.yml/badge.svg?branch=main)](https://github.com/dajudge/s3-resource-operator/actions/workflows/published-release-e2e.yml)
[![GitHub Release](https://img.shields.io/github/v/release/dajudge/s3-resource-operator?display_name=release&sort=semver)](https://github.com/dajudge/s3-resource-operator/releases/latest)
[![License](https://img.shields.io/github/license/dajudge/s3-resource-operator)](LICENSE)
[![Line coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fdajudge%2Fs3-resource-operator%2Fbadges%2Fmetrics%2Fline-coverage.json)](https://github.com/dajudge/s3-resource-operator/tree/badges)
[![Branch coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fdajudge%2Fs3-resource-operator%2Fbadges%2Fmetrics%2Fbranch-coverage.json)](https://github.com/dajudge/s3-resource-operator/tree/badges)
[![PIT mutation score](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fdajudge%2Fs3-resource-operator%2Fbadges%2Fmetrics%2Fmutation-score.json)](https://github.com/dajudge/s3-resource-operator/tree/badges)

Kubernetes operator for declarative S3 users and buckets.

## Status

The project is pre-1.0 and currently exposes `s3.dajudge.com/v1alpha1` APIs. Breaking CRD/API changes may occur in `0.x` releases; upgrade notes will document required migration steps when that happens.

The only supported provider today is VersityGW.

## Install

Each `release/<semver>` tag publishes matching container and Helm chart versions. The chart defaults to the container image with the same version.

```bash
helm install s3-resource-operator \
  oci://registry-1.docker.io/dajudge/s3-resource-operator-chart \
  --version <semver>
```

The operator periodically reconciles managed S3 resources to repair external drift. The default interval is one minute and can be configured through Helm:

```yaml
reconciliation:
  resyncInterval: 1m
```

For example:

```bash
helm install s3-resource-operator \
  oci://registry-1.docker.io/dajudge/s3-resource-operator-chart \
  --version <semver> \
  --set reconciliation.resyncInterval=5m
```

Supported values are positive integer durations using `s`, `m`, or `h` suffixes.

## Configure a Versity backend

Create a Secret containing the VersityGW administrator credentials:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: versity-admin
stringData:
  accessKey: root-access-key
  secretKey: root-secret-key
```

Then create an `S3Backend` in the same namespace:

```yaml
apiVersion: s3.dajudge.com/v1alpha1
kind: S3Backend
metadata:
  name: versity
spec:
  provider: versity
  endpoint: http://versitygw.example.svc.cluster.local:7070
  adminCredentialsSecretRef:
    name: versity-admin
```

References are namespace-local.

## Create a user

```yaml
apiVersion: s3.dajudge.com/v1alpha1
kind: S3User
metadata:
  name: app
spec:
  backendRef: versity
```

Unless configured otherwise, the operator creates a Secret named `<S3User name>-s3`, so this example produces `app-s3`. It contains:

- `accessKey`
- `secretKey`

The generated credentials remain stable across normal reconciliation and Helm upgrades.

## Create a bucket

```yaml
apiVersion: s3.dajudge.com/v1alpha1
kind: S3Bucket
metadata:
  name: app-data
spec:
  backendRef: versity
  userRef: app
```

The external bucket name defaults to the Kubernetes resource name. `spec.bucketName` can override it.

### Bucket deletion policy

`spec.deletionPolicy` defaults to `RETAIN`.

- `RETAIN` removes the Kubernetes resource without deleting the external bucket.
- `DELETE` asks the S3 provider to delete the external bucket before the Kubernetes resource is finalized.

The operator deliberately does **not** purge bucket contents. With `deletionPolicy: DELETE`, deleting a non-empty bucket therefore leaves the `S3Bucket` terminating until the bucket has been emptied and the normal S3 `DeleteBucket` operation can succeed.

An `S3User` cannot be deleted while an `S3Bucket` resource still references it.

## Development

The repository requires JDK 21. Use the checked-in Maven Wrapper as the build entry point; a system Maven installation is not required.

```bash
./mvnw verify
```

The wrapper pins the Maven version used by local development and CI.

## Releases

A successful tagged release creates a GitHub Release for `release/<semver>` only after the native amd64/arm64 images and OCI Helm chart have been published successfully.

Release assets include the packaged Helm chart, `THIRD-PARTY-NOTICES.txt`, and the complete generated runtime dependency license bundle. Native container images also include project and dependency license material under `/licenses`.

Before promoting a release candidate to a stable release, the repository's `Published release E2E` workflow installs the published OCI chart and image into a clean Kind cluster, creates real S3 resources against VersityGW, upgrades that installation to the chart under test, and verifies CRDs, resource identities, generated credentials, reconciliation, and deletion behavior survive the upgrade.

## License

Apache License 2.0. See [LICENSE](LICENSE).
