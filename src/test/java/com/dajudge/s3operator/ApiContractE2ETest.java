package com.dajudge.s3operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserSpec;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

@QuarkusTest
@QuarkusTestResource(K3sVersityTestResource.class)
class ApiContractE2ETest extends OperatorE2ETestSupport {

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
        S3Backend backend = client.resources(S3Backend.class)
                .inNamespace(NS)
                .withName("raw-default-backend")
                .get();
        assertThat(backend.getSpec().getProvider()).isEqualTo("versity");
        createRaw("""
                apiVersion: s3.dajudge.com/v1alpha1
                kind: S3User
                metadata:
                  name: raw-default-user
                spec:
                  backendRef: raw-default-backend
                """);
        S3User user = client.resources(S3User.class)
                .inNamespace(NS)
                .withName("raw-default-user")
                .get();
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
        S3Bucket bucket = client.resources(S3Bucket.class)
                .inNamespace(NS)
                .withName("raw-default-bucket")
                .get();
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
                        """)).isInstanceOf(KubernetesClientException.class);
    }

    @Test
    void defaultsAndGeneratedSecretContractRemainStable() {
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
        client.resources(S3Bucket.class).inNamespace(NS).withName("defaults-bucket").delete();
        awaitDeleted(S3Bucket.class, "defaults-bucket");
        try (S3Client s3 = s3(secretValue(secret, "accessKey"), secretValue(secret, "secretKey"))) {
            awaitAccessible(s3, "defaults-bucket");
        }
        new VersityS3Provider().deleteBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, "defaults-bucket");
    }

    private void createRaw(String yaml) {
        client.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                .inNamespace(NS)
                .create();
    }
}
