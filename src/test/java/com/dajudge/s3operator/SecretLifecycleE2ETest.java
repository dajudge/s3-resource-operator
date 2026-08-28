package com.dajudge.s3operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dajudge.s3operator.api.S3BucketSpec;
import io.fabric8.kubernetes.api.model.Secret;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

@QuarkusTest
@QuarkusTestResource(K3sVersityTestResource.class)
class SecretLifecycleE2ETest extends OperatorE2ETestSupport {

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
