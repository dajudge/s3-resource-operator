# s3-resource-operator

Kubernetes operator for declarative S3 users and buckets.

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

Supported values are positive integer durations using `ms`, `s`, `m`, `h`, or `d` suffixes.

A successful tagged release also creates a GitHub Release for `release/<semver>` with generated release notes and the packaged Helm chart attached. The GitHub Release is created only after the native images and OCI chart have been published successfully.
