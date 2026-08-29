package com.dajudge.s3operator.api;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Required;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class S3BucketSpec {
    @Required
    @JsonPropertyDescription("Name of the S3Backend resource that manages this bucket.")
    private String backendRef;

    @Required
    @JsonPropertyDescription("Name of the S3User resource that owns this bucket.")
    private String userRef;

    @JsonPropertyDescription("S3 bucket name. Defaults to the S3Bucket resource name.")
    private String bucketName;

    @Default("RETAIN")
    @JsonPropertyDescription("Whether the external bucket is retained or deleted when this resource is deleted.")
    private DeletionPolicy deletionPolicy = DeletionPolicy.RETAIN;

    public enum DeletionPolicy {
        RETAIN,
        DELETE
    }
}
