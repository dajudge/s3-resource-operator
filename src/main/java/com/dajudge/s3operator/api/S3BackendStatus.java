package com.dajudge.s3operator.api;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class S3BackendStatus {
    private String state;
    private String message;
}
