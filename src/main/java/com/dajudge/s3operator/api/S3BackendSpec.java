package com.dajudge.s3operator.api;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Required;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class S3BackendSpec {
    @Default("versity")
    @JsonPropertyDescription("S3 provider implementation used by this backend.")
    private String provider = "versity";

    @Required
    @JsonPropertyDescription("S3 endpoint URL used for administrative and bucket operations.")
    private String endpoint;

    @Required
    @JsonPropertyDescription("Secret containing the backend administrator accessKey and secretKey.")
    private LocalObjectReference adminCredentialsSecretRef;
}
