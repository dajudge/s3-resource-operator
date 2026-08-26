package com.dajudge.s3operator.api;

public class S3InstanceSpec {
    private String provider = "versity";
    private String endpoint;
    private SecretRef adminCredentialsSecretRef;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public SecretRef getAdminCredentialsSecretRef() { return adminCredentialsSecretRef; }
    public void setAdminCredentialsSecretRef(SecretRef adminCredentialsSecretRef) { this.adminCredentialsSecretRef = adminCredentialsSecretRef; }

    public static class SecretRef {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
