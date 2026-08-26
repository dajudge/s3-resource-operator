package com.dajudge.s3operator.api;

public class S3BucketSpec {
    private String instanceRef;
    private String ownerRef;
    private String bucketName;
    private DeletionPolicy deletionPolicy = DeletionPolicy.RETAIN;

    public String getInstanceRef() { return instanceRef; }
    public void setInstanceRef(String instanceRef) { this.instanceRef = instanceRef; }
    public String getOwnerRef() { return ownerRef; }
    public void setOwnerRef(String ownerRef) { this.ownerRef = ownerRef; }
    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }
    public DeletionPolicy getDeletionPolicy() { return deletionPolicy; }
    public void setDeletionPolicy(DeletionPolicy deletionPolicy) { this.deletionPolicy = deletionPolicy; }

    public enum DeletionPolicy { RETAIN, DELETE }
}
