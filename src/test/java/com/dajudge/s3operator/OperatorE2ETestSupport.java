package com.dajudge.s3operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserSpec;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

abstract class OperatorE2ETestSupport {
    protected static final String NS = "default";
    protected static final String ROOT_ACCESS = "test-root-access";
    protected static final String ROOT_SECRET = "test-root-secret";
    protected static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Inject KubernetesClient client;
    @ConfigProperty(name = "test.s3.endpoint") String endpoint;

    protected void createAdminSecret(String name) {
        client.secrets()
                .resource(new SecretBuilder()
                        .withNewMetadata()
                        .withName(name)
                        .withNamespace(NS)
                        .endMetadata()
                        .addToStringData("accessKey", ROOT_ACCESS)
                        .addToStringData("secretKey", ROOT_SECRET)
                        .build())
                .create();
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

    protected S3User awaitUser(String name, String status, String reason) {
        return awaitUser(name, status, reason, TIMEOUT);
    }

    protected S3User awaitUser(String name, String status, String reason, Duration timeout) {
        return awaitReady(
                () -> client.resources(S3User.class).inNamespace(NS).withName(name).get(),
                user -> user.getStatus() == null ? null : user.getStatus().getConditions(),
                status,
                reason,
                timeout);
    }

    protected S3Bucket awaitBucket(String name, String status, String reason) {
        return awaitBucket(name, status, reason, TIMEOUT);
    }

    protected S3Bucket awaitBucket(String name, String status, String reason, Duration timeout) {
        return awaitReady(
                () -> client.resources(S3Bucket.class).inNamespace(NS).withName(name).get(),
                bucket -> bucket.getStatus() == null ? null : bucket.getStatus().getConditions(),
                status,
                reason,
                timeout);
    }

    protected Secret awaitSecret(String name) {
        return awaitSecret(name, TIMEOUT);
    }

    protected Secret awaitSecret(String name, Duration timeout) {
        AtomicReference<Secret> result = new AtomicReference<>();
        await().atMost(timeout).untilAsserted(() -> {
            Secret secret = client.secrets().inNamespace(NS).withName(name).get();
            assertThat(secret).isNotNull();
            result.set(secret);
        });
        return result.get();
    }

    protected <T extends HasMetadata> void awaitDeleted(Class<T> type, String name) {
        await().atMost(TIMEOUT).untilAsserted(
                () -> assertThat(client.resources(type).inNamespace(NS).withName(name).get()).isNull());
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

    protected static void awaitAccessible(S3Client s3, String bucket) {
        await().atMost(TIMEOUT).ignoreExceptions().untilAsserted(
                () -> s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build()));
    }

    protected static void awaitRejected(S3Client s3, String bucket) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(credentialsRejected(s3, bucket)).isTrue());
    }

    private <T extends HasMetadata> T awaitReady(
            Supplier<T> lookup,
            Function<T, List<Condition>> conditionLookup,
            String status,
            String reason,
            Duration timeout) {
        AtomicReference<T> result = new AtomicReference<>();
        await().atMost(timeout).untilAsserted(() -> {
            T resource = lookup.get();
            assertThat(resource).isNotNull();
            List<Condition> conditions = conditionLookup.apply(resource);
            assertThat(conditions).isNotNull().isNotEmpty();
            assertThat(conditions.getFirst().getStatus()).isEqualTo(status);
            assertThat(conditions.getFirst().getReason()).isEqualTo(reason);
            result.set(resource);
        });
        return result.get();
    }

    private static boolean credentialsRejected(S3Client s3, String bucket) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return false;
        } catch (S3Exception e) {
            return e.statusCode() == 401 || e.statusCode() == 403;
        }
    }

    protected static String secretValue(Secret secret, String key) {
        return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
    }
}
