# s3-resource-operator

Kubernetes operator for declarative S3 users and buckets.

## Install

Each `release/<semver>` tag publishes matching container and Helm chart versions. The chart defaults to the container image with the same version.

```bash
helm install s3-resource-operator \
  oci://registry-1.docker.io/dajudge/s3-resource-operator-chart \
  --version <semver>
```

A successful tagged release also creates a GitHub Release for `release/<semver>` with generated release notes and the packaged Helm chart attached. The GitHub Release is created only after the native images and OCI chart have been published successfully.
