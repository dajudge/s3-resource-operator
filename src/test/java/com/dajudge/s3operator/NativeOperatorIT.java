package com.dajudge.s3operator;

import static io.fabric8.kubernetes.client.Config.fromKubeconfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserSpec;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@QuarkusIntegrationTest
@QuarkusTestResource(K3sVersityTestResource.class)
class NativeOperatorIT {
    private static final String NS = "default";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void nativeOperatorReconcilesRealResources() throws Exception {
        String kubeconfigPath = System.getProperty("quarkus.kubernetes-client.kubeconfig-file");
        String endpoint = System.getProperty("test.s3.endpoint");
        assertThat(kubeconfigPath).isNotBlank();
        assertThat(endpoint).isNotBlank();
        try (KubernetesClient client = new KubernetesClientBuilder()
                .withConfig(fromKubeconfig(Files.readString(Path.of(kubeconfigPath))))
                .build()) {
            createAdminSecret(client);
            createBackend(client, endpoint);
            createUser(client);
            Secret credentials = awaitCredentials(client);
            String accessKey = secretValue(credentials, "accessKey");
            String secretKey = secretValue(credentials, "secretKey");
            awaitUserReady(client);
            createBucket(client);
            awaitBucketReady(client);
            try (S3Client s3 = s3(endpoint, accessKey, secretKey)) {
                String key = "native-smoke.txt";
                String payload = "hello from the native operator";
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket("native-smoke-bucket")
                                .key(key)
                                .build(),
                        RequestBody.fromString(payload, StandardCharsets.UTF_8));
                assertThat(s3.getObjectAsBytes(GetObjectRequest.builder()
                                        .bucket("native-smoke-bucket")
                                        .key(key)
                                        .build())
                                .asUtf8String())
                        .isEqualTo(payload);
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket("native-smoke-bucket")
                        .key(key)
                        .build());
            }
            client.resources(S3Bucket.class)
                    .inNamespace(NS)
                    .withName("native-smoke-bucket")
                    .delete();
            await().atMost(TIMEOUT)
                    .untilAsserted(() -> assertThat(client.resources(S3Bucket.class)
                                    .inNamespace(NS)
                                    .withName("native-smoke-bucket")
                                    .get())
                            .isNull());
            client.resources(S3User.class)
                    .inNamespace(NS)
                    .withName("native-smoke-user")
                    .delete();
            await().atMost(TIMEOUT)
                    .untilAsserted(() -> assertThat(client.resources(S3User.class)
                                    .inNamespace(NS)
                                    .withName("native-smoke-user")
                                    .get())
                            .isNull());
        }
    }

    private static void createAdminSecret(KubernetesClient client) {
        client.secrets()
                .resource(new SecretBuilder()
                        .withNewMetadata()
                        .withName("native-smoke-admin")
                        .withNamespace(NS)
                        .endMetadata()
                        .addToStringData("accessKey", "test-root-access")
                        .addToStringData("secretKey", "test-root-secret")
                        .build())
                .create();
    }

    private static void createBackend(KubernetesClient client, String endpoint) {
        LocalObjectReference ref = new LocalObjectReference();
        ref.setName("native-smoke-admin");
        S3BackendSpec spec = new S3BackendSpec();
        spec.setProvider("versity");
        spec.setEndpoint(endpoint);
        spec.setAdminCredentialsSecretRef(ref);
        S3Backend backend = new S3Backend();
        backend.setMetadata(new ObjectMetaBuilder()
                .withName("native-smoke-backend")
                .withNamespace(NS)
                .build());
        backend.setSpec(spec);
        client.resources(S3Backend.class).inNamespace(NS).resource(backend).create();
    }

    private static void createUser(KubernetesClient client) {
        S3UserSpec spec = new S3UserSpec();
        spec.setBackendRef("native-smoke-backend");
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder()
                .withName("native-smoke-user")
                .withNamespace(NS)
                .build());
        user.setSpec(spec);
        client.resources(S3User.class).inNamespace(NS).resource(user).create();
    }

    private static void createBucket(KubernetesClient client) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef("native-smoke-backend");
        spec.setUserRef("native-smoke-user");
        spec.setBucketName("native-smoke-bucket");
        spec.setDeletionPolicy(S3BucketSpec.DeletionPolicy.DELETE);
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder()
                .withName("native-smoke-bucket")
                .withNamespace(NS)
                .build());
        bucket.setSpec(spec);
        client.resources(S3Bucket.class).inNamespace(NS).resource(bucket).create();
    }

    private static Secret awaitCredentials(KubernetesClient client) {
        final Secret[] result = new Secret[1];
        await().atMost(TIMEOUT).untilAsserted(() -> {
            Secret secret = client.secrets()
                    .inNamespace(NS)
                    .withName("native-smoke-user-s3")
                    .get();
            assertThat(secret).isNotNull();
            assertThat(secret.getData()).containsKeys("accessKey", "secretKey");
            result[0] = secret;
        });
        return result[0];
    }

    private static void awaitUserReady(KubernetesClient client) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3User user = client.resources(S3User.class)
                    .inNamespace(NS)
                    .withName("native-smoke-user")
                    .get();
            assertThat(user).isNotNull();
            assertThat(user.getStatus()).isNotNull();
            assertThat(user.getStatus().getConditions()).anySatisfy(condition -> {
                assertThat(condition.getType()).isEqualTo("Ready");
                assertThat(condition.getStatus()).isEqualTo("True");
            });
        });
    }

    private static void awaitBucketReady(KubernetesClient client) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3Bucket bucket = client.resources(S3Bucket.class)
                    .inNamespace(NS)
                    .withName("native-smoke-bucket")
                    .get();
            assertThat(bucket).isNotNull();
            assertThat(bucket.getStatus()).isNotNull();
            assertThat(bucket.getStatus().getConditions()).anySatisfy(condition -> {
                assertThat(condition.getType()).isEqualTo("Ready");
                assertThat(condition.getStatus()).isEqualTo("True");
            });
        });
    }

    private static S3Client s3(String endpoint, String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private static String secretValue(Secret secret, String key) {
        return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
    }
}
