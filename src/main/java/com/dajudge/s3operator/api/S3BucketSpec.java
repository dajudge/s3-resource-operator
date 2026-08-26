package com.dajudge.s3operator.api;

public class S3BucketSpec {
    private String instanceRef;
    private String userRef;
    private String bucketName;
    private DeletionPolicy deletionPolicy = DeletionPolicy.RETAIN;

    public String getInstanceRef() { return instanceRef; }
    public void setInstanceRef(String instanceRef) { this.instanceRef = instanceRef; }
    public String getUserRef() { return userRef; }
    public void setUserRef(String userRef) { this.userRef = userRef; }
    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }
    public DeletionPolicy getDeletionPolicy() { return deletionPolicy; }
    public void setDeletionPolicy(DeletionPolicy deletionPolicy) { this.deletionPolicy = deletionPolicy; }

    public enum DeletionPolicy { RETAIN, DELETE }
}
