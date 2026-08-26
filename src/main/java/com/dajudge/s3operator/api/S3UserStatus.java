package com.dajudge.s3operator.api;

import io.fabric8.kubernetes.api.model.Condition;

import java.util.ArrayList;
import java.util.List;

public class S3UserStatus {
    private String accessKeyId;
    private String secretName;
    private Long observedGeneration;
    private List<Condition> conditions = new ArrayList<>();

    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
    public String getSecretName() { return secretName; }
    public void setSecretName(String secretName) { this.secretName = secretName; }
    public Long getObservedGeneration() { return observedGeneration; }
    public void setObservedGeneration(Long observedGeneration) { this.observedGeneration = observedGeneration; }
    public List<Condition> getConditions() { return conditions; }
    public void setConditions(List<Condition> conditions) { this.conditions = conditions; }
}
