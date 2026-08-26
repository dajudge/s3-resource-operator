package com.dajudge.s3operator.provider;

public interface S3Provider {
    String type();

    void createUser(String endpoint, String adminAccessKey, String adminSecretKey,
                    String accessKey, String secretKey, String role);

    void deleteUser(String endpoint, String adminAccessKey, String adminSecretKey,
                    String accessKey);

    void createBucket(String endpoint, String adminAccessKey, String adminSecretKey,
                      String bucketName, String ownerAccessKey);

    void deleteBucket(String endpoint, String adminAccessKey, String adminSecretKey,
                      String bucketName);
}
