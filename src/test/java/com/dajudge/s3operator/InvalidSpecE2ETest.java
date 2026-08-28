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
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(K3sVersityTestResource.class)
class InvalidSpecE2ETest {
    private static final String NS = "default";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Inject
    KubernetesClient client;

    @Test
    void userWithoutSpecReportsInvalidSpec() {
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder()
                .withName("invalid-spec-user")
                .withNamespace(NS)
                .build());
        client.resources(S3User.class).inNamespace(NS).resource(user).create();

        awaitUserInvalidSpec("invalid-spec-user", "S3User spec is required");
    }

    @Test
    void bucketWithoutSpecReportsInvalidSpec() {
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder()
                .withName("invalid-spec-bucket")
                .withNamespace(NS)
                .build());
        client.resources(S3Bucket.class).inNamespace(NS).resource(bucket).create();

        awaitBucketInvalidSpec("invalid-spec-bucket", "S3Bucket spec is required");
    }

    @Test
    void userReferencingIncompleteBackendReportsInvalidSpec() {
        S3BackendSpec backendSpec = new S3BackendSpec();
        S3Backend backend = new S3Backend();
        backend.setMetadata(new ObjectMetaBuilder()
                .withName("incomplete-backend")
                .withNamespace(NS)
                .build());
        backend.setSpec(backendSpec);
        client.resources(S3Backend.class).inNamespace(NS).resource(backend).create();

        S3UserSpec userSpec = new S3UserSpec();
        userSpec.setBackendRef("incomplete-backend");
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder()
                .withName("incomplete-backend-user")
                .withNamespace(NS)
                .build());
        user.setSpec(userSpec);
        client.resources(S3User.class).inNamespace(NS).resource(user).create();

        awaitUserInvalidSpec("incomplete-backend-user", "S3Backend spec.endpoint is required");
    }

    @Test
    void bucketWithMissingReferencesReportsInvalidSpec() {
        S3BucketSpec spec = new S3BucketSpec();
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder()
                .withName("missing-ref-bucket")
                .withNamespace(NS)
                .build());
        bucket.setSpec(spec);
        client.resources(S3Bucket.class).inNamespace(NS).resource(bucket).create();

        awaitBucketInvalidSpec("missing-ref-bucket", "S3Bucket spec.backendRef is required");
    }

    private void awaitUserInvalidSpec(String name, String message) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3User user = client.resources(S3User.class)
                    .inNamespace(NS)
                    .withName(name)
                    .get();
            assertThat(user).isNotNull();
            assertThat(user.getStatus()).isNotNull();
            assertThat(user.getStatus().getConditions()).isNotEmpty();
            Condition condition = user.getStatus().getConditions().getFirst();
            assertThat(condition.getStatus()).isEqualTo("False");
            assertThat(condition.getReason()).isEqualTo("InvalidSpec");
            assertThat(condition.getMessage()).contains(message);
        });
    }

    private void awaitBucketInvalidSpec(String name, String message) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            S3Bucket bucket = client.resources(S3Bucket.class)
                    .inNamespace(NS)
                    .withName(name)
                    .get();
            assertThat(bucket).isNotNull();
            assertThat(bucket.getStatus()).isNotNull();
            assertThat(bucket.getStatus().getConditions()).isNotEmpty();
            Condition condition = bucket.getStatus().getConditions().getFirst();
            assertThat(condition.getStatus()).isEqualTo("False");
            assertThat(condition.getReason()).isEqualTo("InvalidSpec");
            assertThat(condition.getMessage()).contains(message);
        });
    }
}
