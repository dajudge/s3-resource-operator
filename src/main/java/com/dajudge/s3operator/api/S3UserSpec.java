package com.dajudge.s3operator.api;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class S3UserSpec {
    private String backendRef;
    private String secretName;
    private String role = "user";
}
