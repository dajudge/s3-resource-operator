package com.dajudge.s3operator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.Secret;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@QuarkusTest
@QuarkusTestResource(K3sVersityTestResource.class)
@TestProfile(DriftResyncE2ETest.FastResync.class)
class DriftResyncE2ETest extends OperatorE2ETestSupport {

    @Test
    void periodicResyncRepairsExternallyDeletedBucket() {
        createAdminSecret("drift-admin");
        createBackend("drift-backend", "drift-admin");
        createUser("drift-user", "drift-backend");
        awaitUser("drift-user", "True", "Reconciled", Duration.ofSeconds(15));
        Secret credentials = awaitSecret("drift-user-s3", Duration.ofSeconds(15));
        createBucket("drift-bucket", "drift-backend", "drift-user");
        awaitBucket("drift-bucket", "True", "Reconciled", Duration.ofSeconds(15));

        String accessKey = secretValue(credentials, "accessKey");
        String secretKey = secretValue(credentials, "secretKey");
        try (S3Client s3 = s3(accessKey, secretKey)) {
            s3.headBucket(HeadBucketRequest.builder().bucket("drift-bucket").build());
            new VersityS3Provider().deleteBucket(endpoint, ROOT_ACCESS, ROOT_SECRET, "drift-bucket");
            assertThatThrownBy(() -> s3.headBucket(
                            HeadBucketRequest.builder().bucket("drift-bucket").build()))
                    .isInstanceOf(S3Exception.class);
            await().ignoreExceptions().atMost(Duration.ofSeconds(10)).until(() -> {
                s3.headBucket(HeadBucketRequest.builder().bucket("drift-bucket").build());
                return true;
            });
        }
    }

    public static class FastResync implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "s3.operator.resync-interval", "1s",
                    "s3.operator.retry-delay", "1h");
        }
    }
}
