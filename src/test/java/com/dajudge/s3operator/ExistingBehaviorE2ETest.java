package com.dajudge.s3operator;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserSpec;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
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
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@QuarkusTestResource(KindVersityTestResource.class)
class ExistingBehaviorE2ETest {
    private static final String NS = "default";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String ROOT_ACCESS = "test-root-access";
    private static final String ROOT_SECRET = "test-root-secret";

    @Inject KubernetesClient client;
    @ConfigProperty(name = "test.s3.endpoint") String endpoint;

    @Test
    void appliesDefaultsAndPublishesGeneratedSecretContract() {
        assertThat(new S3BackendSpec().getProvider()).isEqualTo("versity");
        assertThat(new S3UserSpec().getRole()).isEqualTo("user");
        assertThat(new S3BucketSpec().getDeletionPolicy()).isEqualTo(S3BucketSpec.DeletionPolicy.RETAIN);

        createAdminSecret("defaults-admin");
        createBackend("defaults-backend", endpoint, "defaults-admin");
        createUser("defaults-user", "defaults-backend", null, null);
        S3User user = awaitUser("defaults-user", "True", "Reconciled");

        Secret secret = awaitSecret("defaults-user-s3");
        assertThat(secret.getData()).containsKeys("accessKey", "secretKey", "endpoint");
        assertThat(secretValue(secret, "accessKey")).isEqualTo(NS + ".defaults-user");
        assertThat(secretValue(secret, "endpoint")).isEqualTo(endpoint);
        assertThat(user.getStatus().getSecretName()).isEqualTo("defaults-user-s3");
        assertThat(user.getStatus().getAccessKeyId()).isEqualTo(NS + ".defaults-user");
        assertThat(secret.getMetadata().getOwnerReferences()).singleElement().satisfies(owner -> {
            assertThat(owner.getKind()).isEqualTo("S3User");
            assertThat(owner.getName()).isEqualTo("defaults-user");
            assertThat(owner.getUid()).isEqualTo(user.getMetadata().getUid());
            assertThat(owner.getController()).isTrue();
            assertThat(owner.getBlockOwnerDeletion()).isTrue();
        });

        createBucket("defaults-bucket", "defaults-backend", "defaults-user", null, null);
        awaitBucket("defaults-bucket", "True", "Reconciled");
        try (S3Client s3 = s3(secretValue(secret, "accessKey"), secretValue(secret, "secretKey"), endpoint)) {
            awaitAccessible(s3, "defaults-bucket");
        }
        client.resources(S3Bucket.class).inNamespace(NS).withName("defaults-bucket").delete();
        awaitDeleted(S3Bucket.class, "defaults-bucket");
        try (S3Client s3 = s3(secretValue(secret, "accessKey"), secretValue(secret, "secretKey"), endpoint)) {
            awaitAccessible(s3, "defaults-bucket");
        }
        new VersityS3Provider().deleteBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, "defaults-bucket");
    }

    @Test
    void recreatesGeneratedSecretAndConvergesMutatedCredentialsOnReconcile() {
        createAdminSecret("secret-life-admin");
        createBackend("secret-life-backend", endpoint, "secret-life-admin");
        createUser("secret-life-user", "secret-life-backend", null, null);
        awaitUser("secret-life-user", "True", "Reconciled");
        Secret initial = awaitSecret("secret-life-user-s3");
        String access = secretValue(initial, "accessKey");
        String originalSecret = secretValue(initial, "secretKey");

        client.secrets().inNamespace(NS).withName("secret-life-user-s3").delete();
        forceUserReconcile("secret-life-user", "admin");
        Secret replacement = awaitSecretWithDifferentSecret("secret-life-user-s3", originalSecret);
        awaitUser("secret-life-user", "True", "Reconciled");
        String replacementSecret = secretValue(replacement, "secretKey");

        createBucket("secret-life-bucket", "secret-life-backend", "secret-life-user", "secret-life-bucket", S3BucketSpec.DeletionPolicy.RETAIN);
        awaitBucket("secret-life-bucket", "True", "Reconciled");
        try (S3Client current = s3(access, replacementSecret, endpoint); S3Client stale = s3(access, originalSecret, endpoint)) {
            awaitAccessible(current, "secret-life-bucket");
            awaitRejected(stale, "secret-life-bucket");
        }

        String manuallyRotated = "manually-rotated-secret-value";
        client.secrets().inNamespace(NS).withName("secret-life-user-s3").edit(secret -> {
            secret.getData().put("secretKey", Base64.getEncoder().encodeToString(manuallyRotated.getBytes(StandardCharsets.UTF_8)));
            return secret;
        });
        forceUserReconcile("secret-life-user", "user");
        awaitUser("secret-life-user", "True", "Reconciled");
        try (S3Client current = s3(access, manuallyRotated, endpoint); S3Client stale = s3(access, replacementSecret, endpoint)) {
            awaitAccessible(current, "secret-life-bucket");
            awaitRejected(stale, "secret-life-bucket");
        }
    }

    @Test
    void cleanupIsIdempotentWhenExternalResourcesAreAlreadyGone() {
        createAdminSecret("cleanup-admin");
        createBackend("cleanup-backend", endpoint, "cleanup-admin");
        createUser("cleanup-user", "cleanup-backend", null, null);
        S3User user = awaitUser("cleanup-user", "True", "Reconciled");
        Secret credentials = awaitSecret("cleanup-user-s3");
        String access = secretValue(credentials, "accessKey");

        createBucket("cleanup-bucket", "cleanup-backend", "cleanup-user", "cleanup-bucket", S3BucketSpec.DeletionPolicy.DELETE);
        awaitBucket("cleanup-bucket", "True", "Reconciled");

        VersityS3Provider provider = new VersityS3Provider();
        provider.deleteBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, "cleanup-bucket");
        client.resources(S3Bucket.class).inNamespace(NS).withName("cleanup-bucket").delete();
        awaitDeleted(S3Bucket.class, "cleanup-bucket");

        provider.deleteUser(endpoint, ROOT_ACCESS, ROOT_SECRET, access);
        client.resources(S3User.class).inNamespace(NS).withName("cleanup-user").delete();
        awaitDeleted(S3User.class, "cleanup-user");
        assertThat(user.getStatus().getAccessKeyId()).isEqualTo(access);
    }

    @Test
    void nonEmptyDeleteKeepsFinalizerUntilBucketIsEmptied() {
        createAdminSecret("nonempty-admin");
        createBackend("nonempty-backend", endpoint, "nonempty-admin");
        createUser("nonempty-user", "nonempty-backend", null, null);
        awaitUser("nonempty-user", "True", "Reconciled");
        Secret credentials = awaitSecret("nonempty-user-s3");

        createBucket("nonempty-bucket", "nonempty-backend", "nonempty-user", "nonempty-bucket", S3BucketSpec.DeletionPolicy.DELETE);
        awaitBucket("nonempty-bucket", "True", "Reconciled");

        try (S3Client s3 = s3(secretValue(credentials, "accessKey"), secretValue(credentials, "secretKey"), endpoint)) {
            s3.putObject(PutObjectRequest.builder().bucket("nonempty-bucket").key("data.txt").build(),
                    RequestBody.fromString("keep me", StandardCharsets.UTF_8));
            client.resources(S3Bucket.class).inNamespace(NS).withName("nonempty-bucket").delete();

            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                S3Bucket terminating = client.resources(S3Bucket.class).inNamespace(NS).withName("nonempty-bucket").get();
                assertThat(terminating).isNotNull();
                assertThat(terminating.getMetadata().getDeletionTimestamp()).isNotNull();
                assertThat(terminating.getMetadata().getFinalizers()).isNotEmpty();
            });

            s3.deleteObject(DeleteObjectRequest.builder().bucket("nonempty-bucket").key("data.txt").build());
        }
        awaitDeleted(S3Bucket.class, "nonempty-bucket");
    }

    @Test
    void conditionTransitionTimeChangesOnlyOnActualTransitions() {
        createAdminSecret("transition-admin");
        createBackend("transition-backend", endpoint, "transition-admin");
        createUser("transition-user", "transition-backend", null, null);
        Condition ready1 = readyCondition(awaitUser("transition-user", "True", "Reconciled"));

        client.secrets().inNamespace(NS).withName("transition-admin").delete();
        forceUserReconcile("transition-user", "admin");
        Condition failed = readyCondition(awaitUser("transition-user", "False", "AdminCredentialsNotFound"));
        assertThat(failed.getLastTransitionTime()).isNotEqualTo(ready1.getLastTransitionTime());

        createAdminSecret("transition-admin");
        forceUserReconcile("transition-user", "user");
        Condition ready2 = readyCondition(awaitUser("transition-user", "True", "Reconciled"));
        assertThat(ready2.getLastTransitionTime()).isNotEqualTo(failed.getLastTransitionTime());
        assertThat(ready2.getLastTransitionTime()).isNotEqualTo(ready1.getLastTransitionTime());
    }

    @Test
    void providerDeletesAreIdempotent() {
        VersityS3Provider provider = new VersityS3Provider();
        String access = "idempotent-provider-user";
        String secret = "idempotent-provider-secret";
        String bucket = "idempotent-provider-bucket";
        provider.createUser(endpoint, ROOT_ACCESS, ROOT_SECRET, access, secret, "user");
        provider.createBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, bucket, access);

        provider.deleteBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, bucket);
        provider.deleteBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, bucket);
        provider.deleteUser(endpoint, ROOT_ACCESS, ROOT_SECRET, access);
        provider.deleteUser(endpoint, ROOT_ACCESS, ROOT_SECRET, access);
    }

    @Test
    void generatedCrdsExposeExpectedKindsPluralsAndEnums() throws Exception {
        String buckets = Files.readString(Path.of("target/kubernetes/s3buckets.s3.dajudge.com-v1.yml"));
        String users = Files.readString(Path.of("target/kubernetes/s3users.s3.dajudge.com-v1.yml"));
        String backends = Files.readString(Path.of("target/kubernetes/s3backends.s3.dajudge.com-v1.yml"));

        assertThat(buckets).contains("kind: \"S3Bucket\"", "plural: \"s3buckets\"", "\"RETAIN\"", "\"DELETE\"");
        assertThat(users).contains("kind: \"S3User\"", "plural: \"s3users\"");
        assertThat(backends).contains("kind: \"S3Backend\"", "plural: \"s3backends\"");
    }

    @Test
    void rawApiRoundTripAppliesDefaultsAndRejectsInvalidEnum() {
        createRaw("""
                apiVersion: s3.dajudge.com/v1alpha1
                kind: S3Backend
                metadata:
                  name: raw-default-backend
                spec:
                  endpoint: http://127.0.0.1:1
                  adminCredentialsSecretRef:
                    name: raw-missing-admin
                """);
        S3Backend backend = client.resources(S3Backend.class).inNamespace(NS).withName("raw-default-backend").get();
        assertThat(backend.getSpec().getProvider()).isEqualTo("versity");

        createRaw("""
                apiVersion: s3.dajudge.com/v1alpha1
                kind: S3User
                metadata:
                  name: raw-default-user
                spec:
                  backendRef: raw-default-backend
                """);
        S3User user = client.resources(S3User.class).inNamespace(NS).withName("raw-default-user").get();
        assertThat(user.getSpec().getRole()).isEqualTo("user");
        assertThat(user.getSpec().getSecretName()).isNull();

        createRaw("""
                apiVersion: s3.dajudge.com/v1alpha1
                kind: S3Bucket
                metadata:
                  name: raw-default-bucket
                spec:
                  backendRef: raw-default-backend
                  userRef: raw-default-user
                """);
        S3Bucket bucket = client.resources(S3Bucket.class).inNamespace(NS).withName("raw-default-bucket").get();
        assertThat(bucket.getSpec().getDeletionPolicy()).isEqualTo(S3BucketSpec.DeletionPolicy.RETAIN);
        assertThat(bucket.getSpec().getBucketName()).isNull();

        assertThatThrownBy(() -> createRaw("""
                apiVersion: s3.dajudge.com/v1alpha1
                kind: S3Bucket
                metadata:
                  name: invalid-policy-bucket
                spec:
                  backendRef: raw-default-backend
                  userRef: raw-default-user
                  deletionPolicy: DESTROY_EVERYTHING
                """))
                .isInstanceOf(KubernetesClientException.class);
    }

    private void createRaw(String yaml) {
        client.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                .inNamespace(NS).create();
    }

    private void createAdminSecret(String name) {
        client.secrets().resource(new SecretBuilder().withNewMetadata().withName(name).withNamespace(NS).endMetadata()
                .addToStringData("accessKey", ROOT_ACCESS).addToStringData("secretKey", ROOT_SECRET).build()).create();
    }

    private void createBackend(String name, String backendEndpoint, String adminSecretName) {
        S3BackendSpec.SecretRef ref = new S3BackendSpec.SecretRef();
        ref.setName(adminSecretName);
        S3BackendSpec spec = new S3BackendSpec();
        spec.setEndpoint(backendEndpoint);
        spec.setAdminCredentialsSecretRef(ref);
        S3Backend backend = new S3Backend();
        backend.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        backend.setSpec(spec);
        client.resources(S3Backend.class).inNamespace(NS).resource(backend).create();
    }

    private void createUser(String name, String backendRef, String secretName, String role) {
        S3UserSpec spec = new S3UserSpec();
        spec.setBackendRef(backendRef);
        if (secretName != null) spec.setSecretName(secretName);
        if (role != null) spec.setRole(role);
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        user.setSpec(spec);
        client.resources(S3User.class).inNamespace(NS).resource(user).create();
    }

    private void createBucket(String name, String backendRef, String userRef, String bucketName, S3BucketSpec.DeletionPolicy policy) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef(backendRef);
        spec.setUserRef(userRef);
        if (bucketName != null) spec.setBucketName(bucketName);
        if (policy != null) spec.setDeletionPolicy(policy);
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        bucket.setSpec(spec);
        client.resources(S3Bucket.class).inNamespace(NS).resource(bucket).create();
    }

    private void forceUserReconcile(String name, String role) {
        client.resources(S3User.class).inNamespace(NS).withName(name).edit(user -> {
            user.getSpec().setRole(role);
            return user;
        });
    }

    private S3User awaitUser(String name, String status, String reason) {
        final S3User[] result = new S3User[1];
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3User user = client.resources(S3User.class).inNamespace(NS).withName(name).get();
            assertThat(user).isNotNull();
            assertThat(user.getStatus()).isNotNull();
            Condition condition = readyCondition(user);
            assertThat(condition.getStatus()).isEqualTo(status);
            assertThat(condition.getReason()).isEqualTo(reason);
            result[0] = user;
        });
        return result[0];
    }

    private S3Bucket awaitBucket(String name, String status, String reason) {
        final S3Bucket[] result = new S3Bucket[1];
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3Bucket bucket = client.resources(S3Bucket.class).inNamespace(NS).withName(name).get();
            assertThat(bucket).isNotNull();
            assertThat(bucket.getStatus()).isNotNull();
            Condition condition = bucket.getStatus().getConditions().stream()
                    .filter(c -> "Ready".equals(c.getType())).findFirst().orElseThrow();
            assertThat(condition.getStatus()).isEqualTo(status);
            assertThat(condition.getReason()).isEqualTo(reason);
            result[0] = bucket;
        });
        return result[0];
    }

    private static Condition readyCondition(S3User user) {
        return user.getStatus().getConditions().stream().filter(c -> "Ready".equals(c.getType())).findFirst().orElseThrow();
    }

    private Secret awaitSecret(String name) {
        final Secret[] result = new Secret[1];
        await().atMost(TIMEOUT).untilAsserted(() -> {
            Secret secret = client.secrets().inNamespace(NS).withName(name).get();
            assertThat(secret).isNotNull();
            result[0] = secret;
        });
        return result[0];
    }

    private Secret awaitSecretWithDifferentSecret(String name, String previous) {
        final Secret[] result = new Secret[1];
        await().atMost(TIMEOUT).untilAsserted(() -> {
            Secret secret = client.secrets().inNamespace(NS).withName(name).get();
            assertThat(secret).isNotNull();
            assertThat(secretValue(secret, "secretKey")).isNotEqualTo(previous);
            result[0] = secret;
        });
        return result[0];
    }

    private <T extends HasMetadata> void awaitDeleted(Class<T> type, String name) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(
                client.resources(type).inNamespace(NS).withName(name).get()).isNull());
    }

    private S3Client s3(String access, String secret, String targetEndpoint) {
        return S3Client.builder().endpointOverride(URI.create(targetEndpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private static void awaitAccessible(S3Client s3, String bucket) {
        await().atMost(TIMEOUT).ignoreExceptions().untilAsserted(() ->
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build()));
    }

    private static void awaitRejected(S3Client s3, String bucket) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(credentialsRejected(s3, bucket)).isTrue());
    }

    private static boolean credentialsRejected(S3Client s3, String bucket) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return false;
        } catch (S3Exception e) {
            return e.statusCode() == 401 || e.statusCode() == 403;
        }
    }

    private static String secretValue(Secret secret, String key) {
        if (secret.getData() != null && secret.getData().containsKey(key))
            return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
        return secret.getStringData().get(key);
    }
}
