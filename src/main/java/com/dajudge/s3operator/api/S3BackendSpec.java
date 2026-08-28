package com.dajudge.s3operator.api;

import io.fabric8.kubernetes.api.model.LocalObjectReference;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class S3BackendSpec {
    private String provider = "versity";
    private String endpoint;
    private LocalObjectReference adminCredentialsSecretRef;
}
