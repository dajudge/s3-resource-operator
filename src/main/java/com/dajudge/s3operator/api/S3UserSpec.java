package com.dajudge.s3operator.api;

public class S3UserSpec {
    private String backendRef;
    private String secretName;
    private String role = "user";

    public String getBackendRef() { return backendRef; }
    public void setBackendRef(String backendRef) { this.backendRef = backendRef; }
    public String getSecretName() { return secretName; }
    public void setSecretName(String secretName) { this.secretName = secretName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
