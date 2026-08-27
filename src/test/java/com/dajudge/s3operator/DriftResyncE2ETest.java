package com.dajudge.s3operator;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserSpec;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@QuarkusTestResource(KindVersityTestResource.class)
@TestProfile(DriftResyncE2ETest.FastResync.class)
class DriftResyncE2ETest {
    private static final String NS = "default";
    private static final String ROOT_ACCESS = "test-root-access";
    private static final String ROOT_SECRET = "test-root-secret";

    @Inject KubernetesClient client;
    @ConfigProperty(name = "test.s3.endpoint") String endpoint;

    @Test
    void periodicResyncRepairsExternallyDeletedBucket() {
        createAdminSecret("drift-admin");
        createBackend("drift-backend", "drift-admin");
        createUser("drift-user", "drift-backend");
        awaitUserReady("drift-user");
        Secret credentials = awaitSecret("drift-user-s3");
        createBucket("drift-bucket", "drift-backend", "drift-user");
        awaitBucketReady("drift-bucket");

        String accessKey = secretValue(credentials, "accessKey");
        String secretKey = secretValue(credentials, "secretKey");
        try (S3Client s3 = s3(accessKey, secretKey)) {
            s3.headBucket(HeadBucketRequest.builder().bucket("drift-bucket").build());
            new VersityS3Provider().deleteBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, "drift-bucket");
            assertThatThrownBy(() -> s3.headBucket(HeadBucketRequest.builder().bucket("drift-bucket").build()))
                    .isInstanceOf(S3Exception.class);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(s3.headBucket(HeadBucketRequest.builder().bucket("drift-bucket").build())).isNotNull());
        }
    }

    private void createAdminSecret(String name) {
        client.secrets().resource(new SecretBuilder().withNewMetadata().withName(name).withNamespace(NS).endMetadata()
                .addToStringData("accessKey", ROOT_ACCESS).addToStringData("secretKey", ROOT_SECRET).build()).create();
    }

    private void createBackend(String name, String adminSecretName) {
        S3BackendSpec.SecretRef ref = new S3BackendSpec.SecretRef();
        ref.setName(adminSecretName);
        S3BackendSpec spec = new S3BackendSpec();
        spec.setEndpoint(endpoint);
        spec.setAdminCredentialsSecretRef(ref);
        S3Backend backend = new S3Backend();
        backend.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        backend.setSpec(spec);
        client.resources(S3Backend.class).inNamespace(NS).resource(backend).create();
    }

    private void createUser(String name, String backendRef) {
        S3UserSpec spec = new S3UserSpec();
        spec.setBackendRef(backendRef);
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        user.setSpec(spec);
        client.resources(S3User.class).inNamespace(NS).resource(user).create();
    }

    private void createBucket(String name, String backendRef, String userRef) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef(backendRef);
        spec.setUserRef(userRef);
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        bucket.setSpec(spec);
        client.resources(S3Bucket.class).inNamespace(NS).resource(bucket).create();
    }

    private void awaitUserReady(String name) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            S3User user = client.resources(S3User.class).inNamespace(NS).withName(name).get();
            assertThat(user.getStatus().getConditions().getFirst().getStatus()).isEqualTo("True");
        });
    }

    private void awaitBucketReady(String name) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            S3Bucket bucket = client.resources(S3Bucket.class).inNamespace(NS).withName(name).get();
            assertThat(bucket.getStatus().getConditions().getFirst().getStatus()).isEqualTo("True");
        });
    }

    private Secret awaitSecret(String name) {
        final Secret[] result = new Secret[1];
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            result[0] = client.secrets().inNamespace(NS).withName(name).get();
            assertThat(result[0]).isNotNull();
        });
        return result[0];
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

    private static String secretValue(Secret secret, String key) {
        return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
    }

    public static class FastResync implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "s3.operator.resync-interval", "1s",
                    "s3.operator.retry-delay", "1h");
        }
    }
}
