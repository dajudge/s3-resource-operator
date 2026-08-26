package com.dajudge.s3operator.api;

public class S3UserSpec {
    private String instanceRef;
    private String secretName;
    private String role = "user";

    public String getInstanceRef() { return instanceRef; }
    public void setInstanceRef(String instanceRef) { this.instanceRef = instanceRef; }
    public String getSecretName() { return secretName; }
    public void setSecretName(String secretName) { this.secretName = secretName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
