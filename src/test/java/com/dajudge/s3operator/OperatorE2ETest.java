package com.dajudge.s3operator;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
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
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@QuarkusTestResource(KindVersityTestResource.class)
class OperatorE2ETest {
    private static final String NAMESPACE = "default";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Inject
    KubernetesClient client;

    @ConfigProperty(name = "test.s3.endpoint")
    String endpoint;

    @Test
    void reconcilesResourcesAndEnforcesBucketLifecycle() {
        createAdminSecret();
        createBackend();
        createUser();

        Secret credentials = awaitSecret("e2e-user-s3");
        String accessKey = secretValue(credentials, "accessKey");
        String secretKey = secretValue(credentials, "secretKey");
        awaitUserReady("e2e-user");

        createBucket("e2e-bucket", S3BucketSpec.DeletionPolicy.RETAIN);
        createBucket("delete-me", S3BucketSpec.DeletionPolicy.DELETE);
        awaitBucketReady("e2e-bucket");
        awaitBucketReady("delete-me");

        try (S3Client s3 = s3(accessKey, secretKey)) {
            String key = "hello.txt";
            String payload = "hello from the real operator";
            await().atMost(TIMEOUT).ignoreExceptions().untilAsserted(() ->
                    s3.putObject(PutObjectRequest.builder().bucket("e2e-bucket").key(key).build(),
                            RequestBody.fromString(payload, StandardCharsets.UTF_8)));
            await().atMost(TIMEOUT).ignoreExceptions().untilAsserted(() ->
                    s3.headBucket(HeadBucketRequest.builder().bucket("delete-me").build()));

            String actual = s3.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket("e2e-bucket")
                            .key(key)
                            .build())
                    .asUtf8String();
            assertThat(actual).isEqualTo(payload);

            client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName("delete-me").delete();
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(
                    client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName("delete-me").get()).isNull());
            await().atMost(TIMEOUT).until(() -> backendBucketMissing(s3, "delete-me"));

            client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName("e2e-bucket").delete();
            await().atMost(TIMEOUT).untilAsserted(() -> assertThat(
                    client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName("e2e-bucket").get()).isNull());
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

    private void createBackend() {
        S3BackendSpec.SecretRef adminRef = new S3BackendSpec.SecretRef();
        adminRef.setName("versity-admin");
        S3BackendSpec spec = new S3BackendSpec();
        spec.setProvider("versity");
        spec.setEndpoint(endpoint);
        spec.setAdminCredentialsSecretRef(adminRef);
        S3Backend backend = new S3Backend();
        backend.setMetadata(new ObjectMetaBuilder().withName("home").withNamespace(NAMESPACE).build());
        backend.setSpec(spec);
        client.resources(S3Backend.class).inNamespace(NAMESPACE).resource(backend).create();
    }

    private void createUser() {
        S3UserSpec spec = new S3UserSpec();
        spec.setBackendRef("home");
        spec.setSecretName("e2e-user-s3");
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder().withName("e2e-user").withNamespace(NAMESPACE).build());
        user.setSpec(spec);
        client.resources(S3User.class).inNamespace(NAMESPACE).resource(user).create();
    }

    private void createBucket(String name, S3BucketSpec.DeletionPolicy deletionPolicy) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef("home");
        spec.setUserRef("e2e-user");
        spec.setBucketName(name);
        spec.setDeletionPolicy(deletionPolicy);
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NAMESPACE).build());
        bucket.setSpec(spec);
        client.resources(S3Bucket.class).inNamespace(NAMESPACE).resource(bucket).create();
    }

    private void awaitUserReady(String name) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3User user = client.resources(S3User.class).inNamespace(NAMESPACE).withName(name).get();
            assertThat(user).isNotNull();
            assertThat(user.getStatus()).isNotNull();
            assertThat(user.getStatus().getObservedGeneration()).isEqualTo(user.getMetadata().getGeneration());
            assertThat(user.getStatus().getConditions())
                    .anySatisfy(condition -> {
                        assertThat(condition.getType()).isEqualTo("Ready");
                        assertThat(condition.getStatus()).isEqualTo("True");
                    });
        });
    }

    private void awaitBucketReady(String name) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3Bucket bucket = client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName(name).get();
            assertThat(bucket).isNotNull();
            assertThat(bucket.getStatus()).isNotNull();
            assertThat(bucket.getStatus().getObservedGeneration()).isEqualTo(bucket.getMetadata().getGeneration());
            assertThat(bucket.getStatus().getConditions())
                    .anySatisfy(condition -> {
                        assertThat(condition.getType()).isEqualTo("Ready");
                        assertThat(condition.getStatus()).isEqualTo("True");
                    });
        });
    }

    private Secret awaitSecret(String name) {
        final Secret[] result = new Secret[1];
        await().atMost(TIMEOUT).untilAsserted(() -> {
            Secret secret = client.secrets().inNamespace(NAMESPACE).withName(name).get();
            assertThat(secret).isNotNull();
            assertThat(secret.getData()).containsKeys("accessKey", "secretKey");
            result[0] = secret;
        });
        return result[0];
    }

    private static boolean backendBucketMissing(S3Client s3, String bucket) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return false;
        } catch (S3Exception e) {
            return e.statusCode() == 404;
        }
    }

    private static String secretValue(Secret secret, String key) {
        return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
    }
}
