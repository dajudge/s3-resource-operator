package com.dajudge.s3operator;

import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3Instance;
import com.dajudge.s3operator.api.S3InstanceSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserSpec;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(KindVersityTestResource.class)
class OperatorE2ETest {
    private static final String NAMESPACE = "default";

    @Inject
    KubernetesClient client;

    @ConfigProperty(name = "test.s3.endpoint")
    String endpoint;

    @Test
    void reconcilesResourcesAndEnforcesBucketLifecycle() throws Exception {
        createAdminSecret();
        createInstance();
        createUser();

        Secret credentials = awaitSecret("e2e-user-s3", Duration.ofSeconds(30));
        String accessKey = secretValue(credentials, "accessKey");
        String secretKey = secretValue(credentials, "secretKey");
        awaitUserReady("e2e-user", Duration.ofSeconds(30));

        createBucket("e2e-bucket", S3BucketSpec.DeletionPolicy.RETAIN);
        createBucket("delete-me", S3BucketSpec.DeletionPolicy.DELETE);
        awaitBucketReady("e2e-bucket", Duration.ofSeconds(30));
        awaitBucketReady("delete-me", Duration.ofSeconds(30));

        try (S3Client s3 = s3(accessKey, secretKey)) {
            String key = "hello.txt";
            String payload = "hello from the real operator";
            awaitBucketAndPut(s3, "e2e-bucket", key, payload, Duration.ofSeconds(30));
            awaitBucket(s3, "delete-me", Duration.ofSeconds(30));

            String actual = s3.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket("e2e-bucket")
                            .key(key)
                            .build())
                    .asUtf8String();
            assertThat(actual).isEqualTo(payload);

            client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName("delete-me").delete();
            awaitBucketResourceDeleted("delete-me", Duration.ofSeconds(30));
            awaitBackendBucketMissing(s3, "delete-me", Duration.ofSeconds(30));

            client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName("e2e-bucket").delete();
            awaitBucketResourceDeleted("e2e-bucket", Duration.ofSeconds(30));
            s3.headBucket(HeadBucketRequest.builder().bucket("e2e-bucket").build());
        }
    }

    private S3Client s3(String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private void createAdminSecret() {
        client.secrets().resource(new SecretBuilder()
                .withNewMetadata()
                .withName("versity-admin")
                .withNamespace(NAMESPACE)
                .endMetadata()
                .addToStringData("accessKey", "test-root-access")
                .addToStringData("secretKey", "test-root-secret")
                .build()).create();
    }

    private void createInstance() {
        S3InstanceSpec.SecretRef adminRef = new S3InstanceSpec.SecretRef();
        adminRef.setName("versity-admin");
        S3InstanceSpec spec = new S3InstanceSpec();
        spec.setProvider("versity");
        spec.setEndpoint(endpoint);
        spec.setAdminCredentialsSecretRef(adminRef);
        S3Instance instance = new S3Instance();
        instance.setMetadata(new ObjectMetaBuilder().withName("home").withNamespace(NAMESPACE).build());
        instance.setSpec(spec);
        client.resources(S3Instance.class).inNamespace(NAMESPACE).resource(instance).create();
    }

    private void createUser() {
        S3UserSpec spec = new S3UserSpec();
        spec.setInstanceRef("home");
        spec.setSecretName("e2e-user-s3");
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder().withName("e2e-user").withNamespace(NAMESPACE).build());
        user.setSpec(spec);
        client.resources(S3User.class).inNamespace(NAMESPACE).resource(user).create();
    }

    private void createBucket(String name, S3BucketSpec.DeletionPolicy deletionPolicy) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setInstanceRef("home");
        spec.setUserRef("e2e-user");
        spec.setBucketName(name);
        spec.setDeletionPolicy(deletionPolicy);
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NAMESPACE).build());
        bucket.setSpec(spec);
        client.resources(S3Bucket.class).inNamespace(NAMESPACE).resource(bucket).create();
    }

    private void awaitUserReady(String name, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            S3User user = client.resources(S3User.class).inNamespace(NAMESPACE).withName(name).get();
            if (user != null && user.getStatus() != null
                    && user.getMetadata().getGeneration().equals(user.getStatus().getObservedGeneration())
                    && user.getStatus().getConditions().stream().anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()))) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for ready S3User " + name);
    }

    private void awaitBucketReady(String name, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            S3Bucket bucket = client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName(name).get();
            if (bucket != null && bucket.getStatus() != null
                    && bucket.getMetadata().getGeneration().equals(bucket.getStatus().getObservedGeneration())
                    && bucket.getStatus().getConditions().stream().anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()))) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for ready S3Bucket " + name);
    }

    private Secret awaitSecret(String name, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Secret secret = client.secrets().inNamespace(NAMESPACE).withName(name).get();
            if (secret != null && secret.getData() != null
                    && secret.getData().containsKey("accessKey") && secret.getData().containsKey("secretKey")) {
                return secret;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for Secret " + name);
    }

    private void awaitBucketResourceDeleted(String name, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName(name).get() == null) return;
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for S3Bucket deletion " + name);
    }

    private static void awaitBucket(S3Client s3, String bucket, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        RuntimeException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                Thread.sleep(250);
            }
        }
        throw new AssertionError("Timed out waiting for bucket " + bucket, lastFailure);
    }

    private static void awaitBackendBucketMissing(S3Client s3, String bucket, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (S3Exception e) {
                if (e.statusCode() == 404) return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for backend bucket deletion " + bucket);
    }

    private static void awaitBucketAndPut(S3Client s3, String bucket, String key, String payload, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        RuntimeException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                        RequestBody.fromString(payload, StandardCharsets.UTF_8));
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                Thread.sleep(250);
            }
        }
        throw new AssertionError("Timed out waiting for bucket " + bucket, lastFailure);
    }

    private static String secretValue(Secret secret, String key) {
        return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
    }
}
