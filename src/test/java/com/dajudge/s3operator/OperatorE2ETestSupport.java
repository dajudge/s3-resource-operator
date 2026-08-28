package com.dajudge.s3operator;

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
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

abstract class OperatorE2ETestSupport {
    protected static final String NS = "default";
    protected static final String ROOT_ACCESS = "test-root-access";
    protected static final String ROOT_SECRET = "test-root-secret";

    @Inject KubernetesClient client;
    @ConfigProperty(name = "test.s3.endpoint") String endpoint;

    protected void createAdminSecret(String name) {
        client.secrets().resource(new SecretBuilder().withNewMetadata().withName(name).withNamespace(NS).endMetadata()
                .addToStringData("accessKey", ROOT_ACCESS).addToStringData("secretKey", ROOT_SECRET).build()).create();
    }

    protected void createBackend(String name, String adminSecretName) {
        LocalObjectReference ref = new LocalObjectReference();
        ref.setName(adminSecretName);
        S3BackendSpec spec = new S3BackendSpec();
        spec.setEndpoint(endpoint);
        spec.setAdminCredentialsSecretRef(ref);
        S3Backend backend = new S3Backend();
        backend.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        backend.setSpec(spec);
        client.resources(S3Backend.class).inNamespace(NS).resource(backend).create();
    }

    protected void createUser(String name, String backendRef) {
        S3UserSpec spec = new S3UserSpec();
        spec.setBackendRef(backendRef);
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        user.setSpec(spec);
        client.resources(S3User.class).inNamespace(NS).resource(user).create();
    }

    protected void createBucket(String name, String backendRef, String userRef) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef(backendRef);
        spec.setUserRef(userRef);
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        bucket.setSpec(spec);
        client.resources(S3Bucket.class).inNamespace(NS).resource(bucket).create();
    }

    protected void awaitUser(String name, String status, String reason, Duration timeout) {
        await().atMost(timeout).untilAsserted(() -> {
            S3User user = client.resources(S3User.class).inNamespace(NS).withName(name).get();
            assertThat(user).isNotNull();
            assertThat(user.getStatus()).isNotNull();
            assertThat(user.getStatus().getConditions()).isNotEmpty();
            assertThat(user.getStatus().getConditions().getFirst().getStatus()).isEqualTo(status);
            assertThat(user.getStatus().getConditions().getFirst().getReason()).isEqualTo(reason);
        });
    }

    protected void awaitBucket(String name, String status, String reason, Duration timeout) {
        await().atMost(timeout).untilAsserted(() -> {
            S3Bucket bucket = client.resources(S3Bucket.class).inNamespace(NS).withName(name).get();
            assertThat(bucket).isNotNull();
            assertThat(bucket.getStatus()).isNotNull();
            assertThat(bucket.getStatus().getConditions()).isNotEmpty();
            assertThat(bucket.getStatus().getConditions().getFirst().getStatus()).isEqualTo(status);
            assertThat(bucket.getStatus().getConditions().getFirst().getReason()).isEqualTo(reason);
        });
    }

    protected Secret awaitSecret(String name, Duration timeout) {
        final Secret[] result = new Secret[1];
        await().atMost(timeout).untilAsserted(() -> {
            result[0] = client.secrets().inNamespace(NS).withName(name).get();
            assertThat(result[0]).isNotNull();
        });
        return result[0];
    }

    protected S3Client s3(String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    protected static String secretValue(Secret secret, String key) {
        return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
    }
}
