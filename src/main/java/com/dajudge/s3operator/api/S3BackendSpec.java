package com.dajudge.s3operator.api;

import io.fabric8.kubernetes.api.model.LocalObjectReference;

public class S3BackendSpec {
    private String provider = "versity";
    private String endpoint;
    private LocalObjectReference adminCredentialsSecretRef;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public LocalObjectReference getAdminCredentialsSecretRef() { return adminCredentialsSecretRef; }
    public void setAdminCredentialsSecretRef(LocalObjectReference adminCredentialsSecretRef) { this.adminCredentialsSecretRef = adminCredentialsSecretRef; }
}
