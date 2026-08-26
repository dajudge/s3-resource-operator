package com.dajudge.s3operator.api;

public class S3UserStatus {
    private String accessKeyId;
    private String secretName;
    private String state;
    private String message;

    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
    public String getSecretName() { return secretName; }
    public void setSecretName(String secretName) { this.secretName = secretName; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
