package com.dajudge.s3operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.Secret;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@QuarkusTest
@QuarkusTestResource(K3sVersityTestResource.class)
class LifecycleBehaviorE2ETest extends OperatorE2ETestSupport {

    @Test
    void recreatesGeneratedSecretAndConvergesMutatedCredentialsOnReconcile() {
        createAdminSecret("secret-life-admin");
        createBackend("secret-life-backend", "secret-life-admin");
        createUser("secret-life-user", "secret-life-backend");
        awaitUser("secret-life-user", "True", "Reconciled");
        Secret initial = awaitSecret("secret-life-user-s3");
        String access = secretValue(initial, "accessKey");
        String originalSecret = secretValue(initial, "secretKey");
        client.secrets().inNamespace(NS).withName("secret-life-user-s3").delete();
        forceUserReconcile("secret-life-user", "admin");
        Secret replacement = awaitSecretWithDifferentSecret("secret-life-user-s3", originalSecret);
        awaitUser("secret-life-user", "True", "Reconciled");
        String replacementSecret = secretValue(replacement, "secretKey");
        createBucket(
                "secret-life-bucket",
                "secret-life-backend",
                "secret-life-user",
                "secret-life-bucket",
                S3BucketSpec.DeletionPolicy.RETAIN);
        awaitBucket("secret-life-bucket", "True", "Reconciled");
        try (S3Client current = s3(access, replacementSecret);
                S3Client stale = s3(access, originalSecret)) {
            awaitAccessible(current, "secret-life-bucket");
            awaitRejected(stale, "secret-life-bucket");
        }
        String manuallyRotated = "manually-rotated-secret-value";
        client.secrets().inNamespace(NS).withName("secret-life-user-s3").edit(secret -> {
            secret.getData()
                    .put(
                            "secretKey",
                            Base64.getEncoder().encodeToString(manuallyRotated.getBytes(StandardCharsets.UTF_8)));
            return secret;
        });
        forceUserReconcile("secret-life-user", "user");
        awaitUser("secret-life-user", "True", "Reconciled");
        try (S3Client current = s3(access, manuallyRotated);
                S3Client stale = s3(access, replacementSecret)) {
            awaitAccessible(current, "secret-life-bucket");
            awaitRejected(stale, "secret-life-bucket");
        }
    }

    @Test
    void cleanupIsIdempotentWhenExternalResourcesAreAlreadyGone() {
        createAdminSecret("cleanup-admin");
        createBackend("cleanup-backend", "cleanup-admin");
        createUser("cleanup-user", "cleanup-backend");
        S3User user = awaitUser("cleanup-user", "True", "Reconciled");
        Secret credentials = awaitSecret("cleanup-user-s3");
        String access = secretValue(credentials, "accessKey");
        createBucket(
                "cleanup-bucket",
                "cleanup-backend",
                "cleanup-user",
                "cleanup-bucket",
                S3BucketSpec.DeletionPolicy.DELETE);
        awaitBucket("cleanup-bucket", "True", "Reconciled");
        VersityS3Provider provider = new VersityS3Provider();
        provider.deleteBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, "cleanup-bucket");
        client.resources(S3Bucket.class)
                .inNamespace(NS)
                .withName("cleanup-bucket")
                .delete();
        awaitDeleted(S3Bucket.class, "cleanup-bucket");
        provider.deleteUser(endpoint, ROOT_ACCESS, ROOT_SECRET, access);
        client.resources(S3User.class).inNamespace(NS).withName("cleanup-user").delete();
        awaitDeleted(S3User.class, "cleanup-user");
        assertThat(user.getStatus().getAccessKeyId()).isEqualTo(access);
    }

    @Test
    void nonEmptyDeleteKeepsFinalizerUntilBucketIsEmptied() {
        createAdminSecret("nonempty-admin");
        createBackend("nonempty-backend", "nonempty-admin");
        createUser("nonempty-user", "nonempty-backend");
        awaitUser("nonempty-user", "True", "Reconciled");
        Secret credentials = awaitSecret("nonempty-user-s3");
        createBucket(
                "nonempty-bucket",
                "nonempty-backend",
                "nonempty-user",
                "nonempty-bucket",
                S3BucketSpec.DeletionPolicy.DELETE);
        awaitBucket("nonempty-bucket", "True", "Reconciled");
        try (S3Client s3 = s3(secretValue(credentials, "accessKey"), secretValue(credentials, "secretKey"))) {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket("nonempty-bucket")
                            .key("data.txt")
                            .build(),
                    RequestBody.fromString("keep me", StandardCharsets.UTF_8));
            client.resources(S3Bucket.class)
                    .inNamespace(NS)
                    .withName("nonempty-bucket")
                    .delete();
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                S3Bucket terminating = client.resources(S3Bucket.class)
                        .inNamespace(NS)
                        .withName("nonempty-bucket")
                        .get();
                assertThat(terminating).isNotNull();
                assertThat(terminating.getMetadata().getDeletionTimestamp()).isNotNull();
                assertThat(terminating.getMetadata().getFinalizers()).isNotEmpty();
            });
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket("nonempty-bucket")
                    .key("data.txt")
                    .build());
        }
        awaitDeleted(S3Bucket.class, "nonempty-bucket");
    }

    @Test
    void conditionTransitionTimeChangesOnlyOnActualTransitions() {
        createAdminSecret("transition-admin");
        createBackend("transition-backend", "transition-admin");
        createUser("transition-user", "transition-backend");
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

    private static Condition readyCondition(S3User user) {
        return user.getStatus().getConditions().stream()
                .filter(c -> "Ready".equals(c.getType()))
                .findFirst()
                .orElseThrow();
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
}
