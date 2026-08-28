package com.dajudge.s3operator;

import static org.assertj.core.api.Assertions.assertThat;

import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserSpec;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.Secret;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

@QuarkusTest
@QuarkusTestResource(K3sVersityTestResource.class)
class ExistingBehaviorE2ETest extends OperatorE2ETestSupport {

    @Test
    void appliesDefaultsAndPublishesGeneratedSecretContract() {
        assertThat(new S3BackendSpec().getProvider()).isEqualTo("versity");
        assertThat(new S3UserSpec().getRole()).isEqualTo("user");
        assertThat(new S3BucketSpec().getDeletionPolicy()).isEqualTo(S3BucketSpec.DeletionPolicy.RETAIN);
        createAdminSecret("defaults-admin");
        createBackend("defaults-backend", "defaults-admin");
        createUser("defaults-user", "defaults-backend");
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
        createBucket("defaults-bucket", "defaults-backend", "defaults-user");
        awaitBucket("defaults-bucket", "True", "Reconciled");
        try (S3Client s3 = s3(secretValue(secret, "accessKey"), secretValue(secret, "secretKey"))) {
            awaitAccessible(s3, "defaults-bucket");
        }
        client.resources(S3Bucket.class)
                .inNamespace(NS)
                .withName("defaults-bucket")
                .delete();
        awaitDeleted(S3Bucket.class, "defaults-bucket");
        try (S3Client s3 = s3(secretValue(secret, "accessKey"), secretValue(secret, "secretKey"))) {
            awaitAccessible(s3, "defaults-bucket");
        }
        new VersityS3Provider().deleteBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, "defaults-bucket");
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
}
