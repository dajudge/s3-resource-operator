package com.dajudge.s3operator.api;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class S3BucketSpec {
    private String backendRef;
    private String userRef;
    private String bucketName;
    private DeletionPolicy deletionPolicy = DeletionPolicy.RETAIN;

    public enum DeletionPolicy {
        RETAIN,
        DELETE
    }
}
