package com.dajudge.s3operator.api;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("s3.dajudge.com")
@Version("v1alpha1")
@Kind("S3Instance")
@Plural("s3instances")
public class S3Instance extends CustomResource<S3InstanceSpec, S3InstanceStatus> implements Namespaced {
}
