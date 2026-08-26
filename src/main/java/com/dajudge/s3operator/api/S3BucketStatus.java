package com.dajudge.s3operator.api;

import io.fabric8.kubernetes.api.model.Condition;

import java.util.ArrayList;
import java.util.List;

public class S3BucketStatus {
    private Long observedGeneration;
    private List<Condition> conditions = new ArrayList<>();

    public Long getObservedGeneration() { return observedGeneration; }
    public void setObservedGeneration(Long observedGeneration) { this.observedGeneration = observedGeneration; }
    public List<Condition> getConditions() { return conditions; }
    public void setConditions(List<Condition> conditions) { this.conditions = conditions; }
}
