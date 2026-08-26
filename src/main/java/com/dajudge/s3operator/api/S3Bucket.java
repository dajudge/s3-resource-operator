package com.dajudge.s3operator.api;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("s3.dajudge.com")
@Version("v1alpha1")
@Kind("S3Bucket")
@Plural("s3buckets")
public class S3Bucket extends CustomResource<S3BucketSpec, S3BucketStatus> implements Namespaced {
}
