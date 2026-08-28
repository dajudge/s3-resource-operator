package com.dajudge.s3operator;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.time.Duration;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(KindVersityTestResource.class)
@TestProfile(LongTimersTestProfile.class)
class DependencyWatchE2ETest extends OperatorE2ETestSupport {

    @Test
    void backendCreationReconcilesWaitingUserWithoutTimer() {
        createAdminSecret("watch-backend-admin");
        createUser("watch-backend-user", "watch-backend");
        awaitUser("watch-backend-user", "False", "BackendNotFound", Duration.ofSeconds(15));

        createBackend("watch-backend", "watch-backend-admin");
        awaitUser("watch-backend-user", "True", "Reconciled", Duration.ofSeconds(10));
    }

    @Test
    void adminSecretCreationReconcilesWaitingUserWithoutTimer() {
        createBackend("watch-secret-backend", "watch-secret-admin");
        createUser("watch-secret-user", "watch-secret-backend");
        awaitUser("watch-secret-user", "False", "AdminCredentialsNotFound", Duration.ofSeconds(15));

        createAdminSecret("watch-secret-admin");
        awaitUser("watch-secret-user", "True", "Reconciled", Duration.ofSeconds(10));
    }

    @Test
    void userCreationReconcilesWaitingBucketWithoutTimer() {
        createAdminSecret("watch-user-admin");
        createBackend("watch-user-backend", "watch-user-admin");
        createBucket("watch-user-bucket", "watch-user-backend", "watch-user");
        awaitBucket("watch-user-bucket", "False", "UserNotFound", Duration.ofSeconds(15));

        createUser("watch-user", "watch-user-backend");
        awaitUser("watch-user", "True", "Reconciled", Duration.ofSeconds(10));
        awaitBucket("watch-user-bucket", "True", "Reconciled", Duration.ofSeconds(10));
    }
}
