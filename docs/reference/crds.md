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
| `adminCredentialsSecretRef` | `object` | Yes | — | Secret containing the backend administrator accessKey and secretKey. |
| `adminCredentialsSecretRef.name` | `string` | No | — | — |
| `endpoint` | `string` | Yes | — | S3 endpoint URL used for administrative and bucket operations. |
| `provider` | `string` | No | `versity` | S3 provider implementation used by this backend. |

### `status`

| Field | Type | Required by CRD | Default | Description |
| --- | --- | :---: | --- | --- |
| `message` | `string` | No | — | — |
| `state` | `string` | No | — | — |

## S3User

API version: `s3.dajudge.com/v1alpha1`  
Scope: Namespaced

### `spec`

| Field | Type | Required by CRD | Default | Description |
| --- | --- | :---: | --- | --- |
| `backendRef` | `string` | Yes | — | Name of the S3Backend resource that manages this user. |
| `role` | `string` | No | `user` | Provider role assigned to the S3 user. |
| `secretName` | `string` | No | — | Name of the generated credentials Secret. Defaults to <resource-name>-s3. |

### `status`

| Field | Type | Required by CRD | Default | Description |
| --- | --- | :---: | --- | --- |
| `accessKeyId` | `string` | No | — | — |
| `conditions` | `array<object>` | No | — | — |
| `observedGeneration` | `integer` | No | — | — |
| `secretName` | `string` | No | — | — |

## S3Bucket

API version: `s3.dajudge.com/v1alpha1`  
Scope: Namespaced

### `spec`

| Field | Type | Required by CRD | Default | Description |
| --- | --- | :---: | --- | --- |
| `backendRef` | `string` | Yes | — | Name of the S3Backend resource that manages this bucket. |
| `bucketName` | `string` | No | — | S3 bucket name. Defaults to the S3Bucket resource name. |
| `deletionPolicy` | `string` (`DELETE` \| `RETAIN`) | No | `RETAIN` | Whether the external bucket is retained or deleted when this resource is deleted. |
| `userRef` | `string` | Yes | — | Name of the S3User resource that owns this bucket. |

### `status`

| Field | Type | Required by CRD | Default | Description |
| --- | --- | :---: | --- | --- |
| `conditions` | `array<object>` | No | — | — |
| `observedGeneration` | `integer` | No | — | — |
