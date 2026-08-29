# Getting started

## Install the operator

Each `release/<semver>` tag publishes matching container and Helm chart versions.

```bash
helm install s3-resource-operator \
  oci://registry-1.docker.io/dajudge/s3-resource-operator-chart \
  --version <semver>
```

## Configure VersityGW

Create administrator credentials in the namespace where the S3 resources will live:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: versity-admin
stringData:
  accessKey: root-access-key
  secretKey: root-secret-key
```

Create the backend:

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

Unless configured otherwise, the operator creates `app-s3`. The generated Secret contains `accessKey`, `secretKey`, and `endpoint`.

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

`spec.deletionPolicy` defaults to `RETAIN`. With `DELETE`, the operator asks the provider to delete the bucket but deliberately does not purge objects first; a non-empty bucket therefore remains terminating until it can be deleted normally.

For the schema-level contract of every field, see the [generated CRD API reference](reference/crds.md).
