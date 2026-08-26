package com.dajudge.s3operator.api;

public class S3InstanceStatus {
    private String state;
    private String message;

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
