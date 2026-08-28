package com.dajudge.s3operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import io.fabric8.kubernetes.api.model.Secret;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@QuarkusTest
@QuarkusTestResource(K3sVersityTestResource.class)
class OperatorE2ETest extends OperatorE2ETestSupport {

    @Test
    void reconcilesResourcesAndEnforcesLifecycleSemantics() {
        ReadyResources ready = createReadyResources();
        try (S3Client s3 = s3(ready.accessKey(), ready.secretKey())) {
            verifyObjectAccess(s3);
            verifyUpdatesPreserveTransitionTimes(s3, ready);
            verifyDeletionSemantics(s3);
        }
        verifyCleanupWithoutBackend();
        verifyCleanupWithoutAdminSecret();
    }

    private ReadyResources createReadyResources() {
        createAdminSecret("versity-admin");
        createBackend("home", "versity-admin");
        createBackend("backend-to-delete", "versity-admin");
        createUser("e2e-user", "home");
        Secret credentials = awaitCredentials("e2e-user-s3");
        S3User user = awaitUserReady("e2e-user");
        createBucket("e2e-bucket", "home", "e2e-user", "e2e-bucket", S3BucketSpec.DeletionPolicy.RETAIN);
        createBucket("delete-me", "home", "e2e-user", "delete-me", S3BucketSpec.DeletionPolicy.DELETE);
        S3Bucket bucket = awaitBucketReady("e2e-bucket");
        awaitBucketReady("delete-me");
        return new ReadyResources(
                secretValue(credentials, "accessKey"),
                secretValue(credentials, "secretKey"),
                user,
                bucket,
                user.getStatus().getConditions().getFirst().getLastTransitionTime(),
                bucket.getStatus().getConditions().getFirst().getLastTransitionTime());
    }

    private void verifyObjectAccess(S3Client s3) {
        String key = "hello.txt";
        String payload = "hello from the real operator";
        await().atMost(TIMEOUT)
                .ignoreExceptions()
                .untilAsserted(() -> s3.putObject(
                        PutObjectRequest.builder().bucket("e2e-bucket").key(key).build(),
                        RequestBody.fromString(payload, StandardCharsets.UTF_8)));
        await().atMost(TIMEOUT)
                .ignoreExceptions()
                .untilAsserted(() -> s3.headBucket(
                        HeadBucketRequest.builder().bucket("delete-me").build()));
        assertThat(s3.getObjectAsBytes(GetObjectRequest.builder()
                                .bucket("e2e-bucket")
                                .key(key)
                                .build())
                        .asUtf8String())
                .isEqualTo(payload);
    }

    private void verifyUpdatesPreserveTransitionTimes(S3Client s3, ReadyResources ready) {
        client.resources(S3User.class)
                .inNamespace(NS)
                .withName("e2e-user")
                .edit(user -> {
                    user.getSpec().setRole("admin");
                    return user;
                });
        client.resources(S3Bucket.class)
                .inNamespace(NS)
                .withName("e2e-bucket")
                .edit(bucket -> {
                    bucket.getSpec().setDeletionPolicy(S3BucketSpec.DeletionPolicy.DELETE);
                    return bucket;
                });
        await().atMost(TIMEOUT).untilAsserted(() -> assertUpdatedResources(s3, ready));
        client.resources(S3Bucket.class)
                .inNamespace(NS)
                .withName("e2e-bucket")
                .edit(bucket -> {
                    bucket.getSpec().setDeletionPolicy(S3BucketSpec.DeletionPolicy.RETAIN);
                    return bucket;
                });
        awaitBucketReady("e2e-bucket");
    }

    private void assertUpdatedResources(S3Client s3, ReadyResources ready) {
        S3User reconciledUser = awaitUserReady("e2e-user");
        S3Bucket reconciledBucket = awaitBucketReady("e2e-bucket");
        assertThat(reconciledUser.getMetadata().getGeneration())
                .isGreaterThan(ready.user().getMetadata().getGeneration());
        assertThat(reconciledBucket.getMetadata().getGeneration())
                .isGreaterThan(ready.bucket().getMetadata().getGeneration());
        assertThat(reconciledUser.getStatus().getConditions().getFirst().getLastTransitionTime())
                .isEqualTo(ready.userTransition());
        assertThat(reconciledBucket.getStatus().getConditions().getFirst().getLastTransitionTime())
                .isEqualTo(ready.bucketTransition());
        s3.headBucket(HeadBucketRequest.builder().bucket("e2e-bucket").build());
    }

    private void verifyDeletionSemantics(S3Client s3) {
        client.resources(S3User.class).inNamespace(NS).withName("e2e-user").delete();
        await().during(Duration.ofSeconds(1)).atMost(TIMEOUT).untilAsserted(() -> {
            S3User terminating = client.resources(S3User.class)
                    .inNamespace(NS)
                    .withName("e2e-user")
                    .get();
            assertThat(terminating).isNotNull();
            assertThat(terminating.getMetadata().getDeletionTimestamp()).isNotNull();
        });
        client.resources(S3Bucket.class).inNamespace(NS).withName("delete-me").delete();
        awaitDeleted(S3Bucket.class, "delete-me");
        await().atMost(TIMEOUT).until(() -> backendBucketMissing(s3, "delete-me"));
        client.resources(S3Bucket.class).inNamespace(NS).withName("e2e-bucket").delete();
        awaitDeleted(S3Bucket.class, "e2e-bucket");
        s3.headBucket(HeadBucketRequest.builder().bucket("e2e-bucket").build());
        client.secrets().inNamespace(NS).withName("e2e-user-s3").delete();
        awaitDeleted(S3User.class, "e2e-user");
        awaitRejected(s3, "e2e-bucket");
    }

    private void verifyCleanupWithoutBackend() {
        createUser("backend-missing-user", "backend-to-delete");
        awaitUserReady("backend-missing-user");
        client.resources(S3Backend.class)
                .inNamespace(NS)
                .withName("backend-to-delete")
                .delete();
        awaitDeleted(S3Backend.class, "backend-to-delete");
        client.resources(S3User.class)
                .inNamespace(NS)
                .withName("backend-missing-user")
                .delete();
        awaitDeleted(S3User.class, "backend-missing-user");
    }

    private void verifyCleanupWithoutAdminSecret() {
        createAdminSecret("temporary-admin");
        createBackend("admin-missing", "temporary-admin");
        createUser("admin-missing-user", "admin-missing");
        awaitCredentials("admin-missing-user-s3");
        createBucket(
                "admin-missing-bucket",
                "admin-missing",
                "admin-missing-user",
                "admin-missing-bucket",
                S3BucketSpec.DeletionPolicy.DELETE);
        awaitBucketReady("admin-missing-bucket");
        client.secrets().inNamespace(NS).withName("temporary-admin").delete();
        client.resources(S3Bucket.class)
                .inNamespace(NS)
                .withName("admin-missing-bucket")
                .delete();
        awaitDeleted(S3Bucket.class, "admin-missing-bucket");
        client.resources(S3User.class)
                .inNamespace(NS)
                .withName("admin-missing-user")
                .delete();
        awaitDeleted(S3User.class, "admin-missing-user");
    }

    private S3User awaitUserReady(String name) {
        S3User user = awaitUser(name, "True", "Reconciled");
        assertThat(user.getStatus().getObservedGeneration()).isEqualTo(user.getMetadata().getGeneration());
        return user;
    }

    private S3Bucket awaitBucketReady(String name) {
        S3Bucket bucket = awaitBucket(name, "True", "Reconciled");
        assertThat(bucket.getStatus().getObservedGeneration()).isEqualTo(bucket.getMetadata().getGeneration());
        return bucket;
    }

    private Secret awaitCredentials(String name) {
        Secret secret = awaitSecret(name);
        assertThat(secret.getData()).containsKeys("accessKey", "secretKey");
        return secret;
    }

    private static boolean backendBucketMissing(S3Client s3, String bucket) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return false;
        } catch (S3Exception e) {
            return e.statusCode() == 404;
        }
    }

    private record ReadyResources(
            String accessKey,
            String secretKey,
            S3User user,
            S3Bucket bucket,
            String userTransition,
            String bucketTransition) {}
}
