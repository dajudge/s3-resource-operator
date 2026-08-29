# CRD API reference

> This file is generated from `charts/s3-resource-operator/crds/s3.dajudge.com.yaml`. Do not edit it by hand.
>
> Requiredness, defaults, enums, and descriptions below reflect the **shipped CRD OpenAPI schema**, not application-side fallback behavior.

## S3Backend

API version: `s3.dajudge.com/v1alpha1`  
Scope: Namespaced

### `spec`

| Field | Type | Required by CRD | Default | Description |
| --- | --- | :---: | --- | --- |
| `provider` | `string` | No | — | — |
| `endpoint` | `string` | No | — | — |
| `adminCredentialsSecretRef` | `object` | No | — | — |
| `adminCredentialsSecretRef.name` | `string` | No | — | — |

### `status`

The CRD preserves unknown status fields (`x-kubernetes-preserve-unknown-fields`).

## S3User

API version: `s3.dajudge.com/v1alpha1`  
Scope: Namespaced

### `spec`

| Field | Type | Required by CRD | Default | Description |
| --- | --- | :---: | --- | --- |
| `backendRef` | `string` | No | — | — |
| `secretName` | `string` | No | — | — |
| `role` | `string` | No | — | — |

### `status`

The CRD preserves unknown status fields (`x-kubernetes-preserve-unknown-fields`).

## S3Bucket

API version: `s3.dajudge.com/v1alpha1`  
Scope: Namespaced

### `spec`

| Field | Type | Required by CRD | Default | Description |
| --- | --- | :---: | --- | --- |
| `backendRef` | `string` | No | — | — |
| `userRef` | `string` | No | — | — |
| `bucketName` | `string` | No | — | — |
| `deletionPolicy` | `string` (`DELETE` \| `RETAIN`) | No | — | — |

### `status`

The CRD preserves unknown status fields (`x-kubernetes-preserve-unknown-fields`).
