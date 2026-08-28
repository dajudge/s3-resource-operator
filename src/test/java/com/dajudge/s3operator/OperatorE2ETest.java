package com.dajudge.s3operator;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserSpec;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
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
@QuarkusTestResource(K3sVersityTestResource.class)
class OperatorE2ETest {
    private static final String NAMESPACE = "default";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Inject KubernetesClient client;
    @ConfigProperty(name = "test.s3.endpoint") String endpoint;

    @Test
    void reconcilesResourcesAndEnforcesLifecycleSemantics() {
        createAdminSecret();
        createBackend("home", "versity-admin");
        createBackend("backend-to-delete", "versity-admin");
        createUser("e2e-user", "home");
        Secret credentials = awaitSecret("e2e-user-s3");
        String accessKey = secretValue(credentials, "accessKey");
        String secretKey = secretValue(credentials, "secretKey");
        S3User readyUser = awaitUserReady("e2e-user");
        String originalUserTransition = readyUser.getStatus().getConditions().getFirst().getLastTransitionTime();
        createBucket("e2e-bucket", "home", "e2e-user", S3BucketSpec.DeletionPolicy.RETAIN);
        createBucket("delete-me", "home", "e2e-user", S3BucketSpec.DeletionPolicy.DELETE);
        S3Bucket readyBucket = awaitBucketReady("e2e-bucket");
        String originalBucketTransition = readyBucket.getStatus().getConditions().getFirst().getLastTransitionTime();
        awaitBucketReady("delete-me");

        try (S3Client s3 = s3(accessKey, secretKey)) {
            String key = "hello.txt";
            String payload = "hello from the real operator";
            await().atMost(TIMEOUT).ignoreExceptions().untilAsserted(() -> s3.putObject(
                    PutObjectRequest.builder().bucket("e2e-bucket").key(key).build(),
                    RequestBody.fromString(payload, StandardCharsets.UTF_8)));
            await().atMost(TIMEOUT).ignoreExceptions().untilAsserted(() ->
                    s3.headBucket(HeadBucketRequest.builder().bucket("delete-me").build()));
            assertThat(s3.getObjectAsBytes(GetObjectRequest.builder().bucket("e2e-bucket").key(key).build()).asUtf8String()).isEqualTo(payload);

            client.resources(S3User.class).inNamespace(NAMESPACE).withName("e2e-user").edit(user -> {
                user.getSpec().setRole("admin"); return user;
            });
            client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName("e2e-bucket").edit(bucket -> {
                bucket.getSpec().setDeletionPolicy(S3BucketSpec.DeletionPolicy.DELETE); return bucket;
            });
            await().atMost(TIMEOUT).untilAsserted(() -> {
                S3User reconciledUser = awaitUserReady("e2e-user");
                S3Bucket reconciledBucket = awaitBucketReady("e2e-bucket");
                assertThat(reconciledUser.getMetadata().getGeneration()).isGreaterThan(readyUser.getMetadata().getGeneration());
                assertThat(reconciledBucket.getMetadata().getGeneration()).isGreaterThan(readyBucket.getMetadata().getGeneration());
                assertThat(reconciledUser.getStatus().getConditions().getFirst().getLastTransitionTime()).isEqualTo(originalUserTransition);
                assertThat(reconciledBucket.getStatus().getConditions().getFirst().getLastTransitionTime()).isEqualTo(originalBucketTransition);
                s3.headBucket(HeadBucketRequest.builder().bucket("e2e-bucket").build());
            });
            client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName("e2e-bucket").edit(bucket -> {
                bucket.getSpec().setDeletionPolicy(S3BucketSpec.DeletionPolicy.RETAIN); return bucket;
            });
            awaitBucketReady("e2e-bucket");

            client.resources(S3User.class).inNamespace(NAMESPACE).withName("e2e-user").delete();
            await().during(Duration.ofSeconds(1)).atMost(TIMEOUT).untilAsserted(() -> {
                S3User terminating = client.resources(S3User.class).inNamespace(NAMESPACE).withName("e2e-user").get();
                assertThat(terminating).isNotNull();
                assertThat(terminating.getMetadata().getDeletionTimestamp()).isNotNull();
            });

            client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName("delete-me").delete();
            awaitResourceDeleted(S3Bucket.class, "delete-me");
            await().atMost(TIMEOUT).until(() -> backendBucketMissing(s3, "delete-me"));
            client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName("e2e-bucket").delete();
            awaitResourceDeleted(S3Bucket.class, "e2e-bucket");
            s3.headBucket(HeadBucketRequest.builder().bucket("e2e-bucket").build());
            client.secrets().inNamespace(NAMESPACE).withName("e2e-user-s3").delete();
            awaitResourceDeleted(S3User.class, "e2e-user");
            await().atMost(TIMEOUT).until(() -> credentialsRejected(s3, "e2e-bucket"));
        }

        createUser("backend-missing-user", "backend-to-delete");
        awaitUserReady("backend-missing-user");
        client.resources(S3Backend.class).inNamespace(NAMESPACE).withName("backend-to-delete").delete();
        awaitResourceDeleted(S3Backend.class, "backend-to-delete");
        client.resources(S3User.class).inNamespace(NAMESPACE).withName("backend-missing-user").delete();
        awaitResourceDeleted(S3User.class, "backend-missing-user");

        createAdminSecret("temporary-admin");
        createBackend("admin-missing", "temporary-admin");
        createUser("admin-missing-user", "admin-missing");
        awaitSecret("admin-missing-user-s3");
        createBucket("admin-missing-bucket", "admin-missing", "admin-missing-user", S3BucketSpec.DeletionPolicy.DELETE);
        awaitBucketReady("admin-missing-bucket");
        client.secrets().inNamespace(NAMESPACE).withName("temporary-admin").delete();
        client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName("admin-missing-bucket").delete();
        awaitResourceDeleted(S3Bucket.class, "admin-missing-bucket");
        client.resources(S3User.class).inNamespace(NAMESPACE).withName("admin-missing-user").delete();
        awaitResourceDeleted(S3User.class, "admin-missing-user");
    }

    private S3Client s3(String accessKey, String secretKey) {
        return S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private void createAdminSecret() { createAdminSecret("versity-admin"); }

    private void createAdminSecret(String name) {
        client.secrets().resource(new SecretBuilder().withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata()
                .addToStringData("accessKey", "test-root-access").addToStringData("secretKey", "test-root-secret").build()).create();
    }

    private void createBackend(String name, String adminSecretName) {
        LocalObjectReference adminRef = new LocalObjectReference();
        adminRef.setName(adminSecretName);
        S3BackendSpec spec = new S3BackendSpec();
        spec.setProvider("versity");
        spec.setEndpoint(endpoint);
        spec.setAdminCredentialsSecretRef(adminRef);
        S3Backend backend = new S3Backend();
        backend.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NAMESPACE).build());
        backend.setSpec(spec);
        client.resources(S3Backend.class).inNamespace(NAMESPACE).resource(backend).create();
    }

    private void createUser(String name, String backendRef) {
        S3UserSpec spec = new S3UserSpec();
        spec.setBackendRef(backendRef);
        spec.setSecretName(name + "-s3");
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NAMESPACE).build());
        user.setSpec(spec);
        client.resources(S3User.class).inNamespace(NAMESPACE).resource(user).create();
    }

    private void createBucket(String name, String backendRef, String userRef, S3BucketSpec.DeletionPolicy deletionPolicy) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef(backendRef);
        spec.setUserRef(userRef);
        spec.setBucketName(name);
        spec.setDeletionPolicy(deletionPolicy);
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NAMESPACE).build());
        bucket.setSpec(spec);
        client.resources(S3Bucket.class).inNamespace(NAMESPACE).resource(bucket).create();
    }

    private S3User awaitUserReady(String name) {
        final S3User[] result = new S3User[1];
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3User user = client.resources(S3User.class).inNamespace(NAMESPACE).withName(name).get();
            assertThat(user).isNotNull();
            assertThat(user.getStatus()).isNotNull();
            assertThat(user.getStatus().getObservedGeneration()).isEqualTo(user.getMetadata().getGeneration());
            assertThat(user.getStatus().getConditions()).anySatisfy(condition -> {
                assertThat(condition.getType()).isEqualTo("Ready");
                assertThat(condition.getStatus()).isEqualTo("True");
            });
            result[0] = user;
        });
        return result[0];
    }

    private S3Bucket awaitBucketReady(String name) {
        final S3Bucket[] result = new S3Bucket[1];
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3Bucket bucket = client.resources(S3Bucket.class).inNamespace(NAMESPACE).withName(name).get();
            assertThat(bucket).isNotNull();
            assertThat(bucket.getStatus()).isNotNull();
            assertThat(bucket.getStatus().getObservedGeneration()).isEqualTo(bucket.getMetadata().getGeneration());
            assertThat(bucket.getStatus().getConditions()).anySatisfy(condition -> {
                assertThat(condition.getType()).isEqualTo("Ready");
                assertThat(condition.getStatus()).isEqualTo("True");
            });
            result[0] = bucket;
        });
        return result[0];
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

    private <T extends HasMetadata> void awaitResourceDeleted(Class<T> type, String name) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(client.resources(type).inNamespace(NAMESPACE).withName(name).get()).isNull());
    }

    private static boolean backendBucketMissing(S3Client s3, String bucket) {
        try { s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build()); return false; }
        catch (S3Exception e) { return e.statusCode() == 404; }
    }

    private static boolean credentialsRejected(S3Client s3, String bucket) {
        try { s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build()); return false; }
        catch (S3Exception e) { return e.statusCode() == 403 || e.statusCode() == 401; }
    }

    private static String secretValue(Secret secret, String key) {
        return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
    }
}
