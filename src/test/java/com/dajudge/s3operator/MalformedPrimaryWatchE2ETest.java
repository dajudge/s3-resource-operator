package com.dajudge.s3operator;

import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3User;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

@QuarkusTest
@QuarkusTestResource(KindVersityTestResource.class)
@TestProfile(MalformedPrimaryWatchE2ETest.LongTimers.class)
class MalformedPrimaryWatchE2ETest extends OperatorE2ETestSupport {

    @Test
    void malformedUserDoesNotBlockBackendEventForValidUser() {
        createMalformedUser("malformed-user");
        awaitUser("malformed-user", "False", "InvalidSpec", Duration.ofSeconds(15));

        createAdminSecret("isolated-backend-admin");
        createUser("isolated-user", "isolated-backend");
        awaitUser("isolated-user", "False", "BackendNotFound", Duration.ofSeconds(15));

        createBackend("isolated-backend", "isolated-backend-admin");
        awaitUser("isolated-user", "True", "Reconciled", Duration.ofSeconds(10));
    }

    @Test
    void malformedBucketDoesNotBlockUserEventForValidBucket() {
        createMalformedBucket("malformed-bucket");
        awaitBucket("malformed-bucket", "False", "InvalidSpec", Duration.ofSeconds(15));

        createAdminSecret("isolated-user-admin");
        createBackend("isolated-user-backend", "isolated-user-admin");
        createBucket("isolated-bucket", "isolated-user-backend", "isolated-owner");
        awaitBucket("isolated-bucket", "False", "UserNotFound", Duration.ofSeconds(15));

        createUser("isolated-owner", "isolated-user-backend");
        awaitUser("isolated-owner", "True", "Reconciled", Duration.ofSeconds(10));
        awaitBucket("isolated-bucket", "True", "Reconciled", Duration.ofSeconds(10));
    }

    private void createMalformedUser(String name) {
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        client.resources(S3User.class).inNamespace(NS).resource(user).create();
    }

    private void createMalformedBucket(String name) {
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        client.resources(S3Bucket.class).inNamespace(NS).resource(bucket).create();
    }

    public static class LongTimers implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "s3.operator.resync-interval", "1h",
                    "s3.operator.retry-delay", "1h");
        }
    }
}
