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
    private static final String ACCESS = "provider-drift-user";
    private static final String INITIAL_SECRET = "provider-initial-secret";
    private static final String ROTATED_SECRET = "provider-rotated-secret";
    private static final String BUCKET = "provider-drift-bucket";

    @ConfigProperty(name = "test.s3.endpoint")
    String endpoint;

    @Test
    void convergesExistingUserSecretAndRole() {
        VersityS3Provider provider = new VersityS3Provider();

        provider.createUser(endpoint, ROOT_ACCESS, ROOT_SECRET, ACCESS, INITIAL_SECRET, "user");
        provider.createBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, BUCKET, ACCESS);

        try (S3Client initial = s3(INITIAL_SECRET)) {
            await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                    initial.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build()));
        }

        provider.createUser(endpoint, ROOT_ACCESS, ROOT_SECRET, ACCESS, ROTATED_SECRET, "admin");

        try (S3Client rotated = s3(ROTATED_SECRET)) {
            await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                    rotated.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build()));
        }

        try (S3Client stale = s3(INITIAL_SECRET)) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(credentialsRejected(stale)).isTrue());
        } finally {
            provider.deleteBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, BUCKET);
            provider.deleteUser(endpoint, ROOT_ACCESS, ROOT_SECRET, ACCESS);
        }
    }

    private S3Client s3(String secret) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, secret)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private static boolean credentialsRejected(S3Client s3) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build());
            return false;
        } catch (S3Exception e) {
            return e.statusCode() == 401 || e.statusCode() == 403;
        }
    }
}
