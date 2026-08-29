package com.dajudge.s3operator.api;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Required;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class S3UserSpec {
    @Required
    @JsonPropertyDescription("Name of the S3Backend resource that manages this user.")
    private String backendRef;

    @JsonPropertyDescription("Name of the generated credentials Secret. Defaults to <resource-name>-s3.")
    private String secretName;

    @Default("user")
    @JsonPropertyDescription("Provider role assigned to the S3 user.")
    private String role = "user";
}
