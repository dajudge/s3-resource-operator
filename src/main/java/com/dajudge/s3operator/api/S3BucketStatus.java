package com.dajudge.s3operator.api;

import io.fabric8.kubernetes.api.model.Condition;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class S3BucketStatus {
    private Long observedGeneration;
    private List<Condition> conditions = new ArrayList<>();
}
