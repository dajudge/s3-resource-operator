package com.dajudge.s3operator;

import com.dajudge.s3operator.provider.VersityS3Provider;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@QuarkusTestResource(KindVersityTestResource.class)
class VersityProviderE2ETest {
    private static final String ROOT_ACCESS = "test-root-access";
    private static final String ROOT_SECRET = "test-root-secret";

    @ConfigProperty(name = "test.s3.endpoint")
    String endpoint;

    @Test
    void convergesExistingUserSecretAndRole() {
        String access = "provider-drift-user";
        String initialSecret = "provider-initial-secret";
        String rotatedSecret = "provider-rotated-secret";
        String bucket = "provider-drift-bucket";
        VersityS3Provider provider = new VersityS3Provider();

        provider.createUser(endpoint, ROOT_ACCESS, ROOT_SECRET, access, initialSecret, "user");
        provider.createBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, bucket, access);

        try (S3Client initial = s3(access, initialSecret)) {
            awaitAccessible(initial, bucket);
        }

        provider.createUser(endpoint, ROOT_ACCESS, ROOT_SECRET, access, rotatedSecret, "admin");

        try (S3Client rotated = s3(access, rotatedSecret)) {
            awaitAccessible(rotated, bucket);
        }

        try (S3Client stale = s3(access, initialSecret)) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(credentialsRejected(stale, bucket)).isTrue());
        } finally {
            provider.deleteBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, bucket);
            provider.deleteUser(endpoint, ROOT_ACCESS, ROOT_SECRET, access);
        }
    }

    @Test
    void convergesExistingBucketOwner() {
        String firstAccess = "provider-owner-a";
        String firstSecret = "provider-owner-a-secret";
        String secondAccess = "provider-owner-b";
        String secondSecret = "provider-owner-b-secret";
        String bucket = "provider-owner-drift-bucket";
        VersityS3Provider provider = new VersityS3Provider();

        provider.createUser(endpoint, ROOT_ACCESS, ROOT_SECRET, firstAccess, firstSecret, "user");
        provider.createUser(endpoint, ROOT_ACCESS, ROOT_SECRET, secondAccess, secondSecret, "user");
        provider.createBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, bucket, firstAccess);

        try (S3Client firstOwner = s3(firstAccess, firstSecret)) {
            awaitAccessible(firstOwner, bucket);
        }

        provider.createBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, bucket, secondAccess);

        try (S3Client secondOwner = s3(secondAccess, secondSecret)) {
            awaitAccessible(secondOwner, bucket);
        }

        try (S3Client formerOwner = s3(firstAccess, firstSecret)) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(credentialsRejected(formerOwner, bucket)).isTrue());
        } finally {
            provider.deleteBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, bucket);
            provider.deleteUser(endpoint, ROOT_ACCESS, ROOT_SECRET, firstAccess);
            provider.deleteUser(endpoint, ROOT_ACCESS, ROOT_SECRET, secondAccess);
        }
    }

    private S3Client s3(String access, String secret) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private static void awaitAccessible(S3Client s3, String bucket) {
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build()));
    }

    private static boolean credentialsRejected(S3Client s3, String bucket) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return false;
        } catch (S3Exception e) {
            return e.statusCode() == 401 || e.statusCode() == 403;
        }
    }
}
