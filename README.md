# s3-resource-operator

Kubernetes operator for declarative S3 users and buckets.

## Installation

Releases are published from `release/<semver>` Git tags. The Helm chart and container image always use the same version, and the chart defaults to the matching image tag.

```bash
helm install s3-resource-operator \
  oci://registry-1.docker.io/dajudge/s3-resource-operator-chart \
  --version 1.2.3 \
  --namespace s3-resource-operator \
  --create-namespace
```

For release `1.2.3`, the chart installs `dajudge/s3-resource-operator:1.2.3` by default. `image.tag` can still be overridden explicitly when required.
