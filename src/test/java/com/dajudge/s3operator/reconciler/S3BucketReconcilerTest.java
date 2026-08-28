package com.dajudge.s3operator.reconciler;

import static com.dajudge.s3operator.reconciler.Fabric8TestMocks.stubResourceGet;
import static com.dajudge.s3operator.reconciler.Fabric8TestMocks.stubSecrets;
import static com.dajudge.s3operator.reconciler.ReconcilerTestFixtures.ENDPOINT;
import static com.dajudge.s3operator.reconciler.ReconcilerTestFixtures.adminSecret;
import static com.dajudge.s3operator.reconciler.ReconcilerTestFixtures.backend;
import static com.dajudge.s3operator.reconciler.ReconcilerTestFixtures.bucket;
import static com.dajudge.s3operator.reconciler.ReconcilerTestFixtures.user;
import static com.dajudge.s3operator.reconciler.ReconcilerTestFixtures.userSecret;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.provider.S3ProviderException;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class S3BucketReconcilerTest {

    @Test
    void reconcilesBucketAndPublishesReadyStatus() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        S3Bucket bucket = bucket("photos", "backend", "alice", null, S3BucketSpec.DeletionPolicy.RETAIN);
        stubDependencies(client, provider, backend(), user("alice", "backend", null), userSecret("alice-s3"), adminSecret());

        var control = reconciler(client, provider).reconcile(bucket, mock(Context.class));

        assertThat(control).isNotNull();
        verify(provider).createBucket(ENDPOINT, "admin-access", "admin-secret", "photos", "alice-access");
        assertThat(bucket.getStatus().getObservedGeneration()).isEqualTo(9L);
        assertThat(bucket.getStatus().getConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.getType()).isEqualTo("Ready");
            assertThat(condition.getStatus()).isEqualTo("True");
            assertThat(condition.getReason()).isEqualTo("Reconciled");
            assertThat(condition.getMessage()).isEqualTo("Versity bucket is ready");
            assertThat(condition.getObservedGeneration()).isEqualTo(9L);
            assertThat(condition.getLastTransitionTime()).isNotBlank();
        });
    }

    @Test
    void usesConfiguredBucketNameAndUserSecretName() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        S3Bucket bucket =
                bucket("resource-name", "backend", "alice", "actual-bucket", S3BucketSpec.DeletionPolicy.RETAIN);
        stubDependencies(
                client,
                provider,
                backend(),
                user("alice", "backend", "custom-secret"),
                userSecret("custom-secret"),
                adminSecret());

        assertThat(reconciler(client, provider).reconcile(bucket, mock(Context.class)))
                .isNotNull();

        verify(provider).createBucket(ENDPOINT, "admin-access", "admin-secret", "actual-bucket", "alice-access");
    }

    @Test
    void invalidSpecsBecomeRetryableStatuses() {
        VersityS3Provider provider = mock(VersityS3Provider.class);
        KubernetesClient invalidBucketClient = mock(KubernetesClient.class);
        S3Bucket invalidBucket = bucket("photos", null, "alice", null, S3BucketSpec.DeletionPolicy.RETAIN);

        assertThat(reconciler(invalidBucketClient, provider).reconcile(invalidBucket, mock(Context.class)))
                .isNotNull();
        assertFailure(invalidBucket, "InvalidSpec", "S3Bucket spec.backendRef is required");

        KubernetesClient invalidBackendClient = mock(KubernetesClient.class);
        S3Bucket validBucket = bucket("photos", "backend", "alice", null, S3BucketSpec.DeletionPolicy.RETAIN);
        S3Backend invalidBackend = new S3Backend();
        invalidBackend.setSpec(new S3BackendSpec());
        stubResourceGet(invalidBackendClient, S3Backend.class, "backend", invalidBackend);

        assertThat(reconciler(invalidBackendClient, provider).reconcile(validBucket, mock(Context.class)))
                .isNotNull();
        assertFailure(validBucket, "InvalidSpec", "S3Backend spec.endpoint is required");

        KubernetesClient invalidUserClient = mock(KubernetesClient.class);
        S3Bucket bucketWithInvalidUser = bucket("photos", "backend", "alice", null, S3BucketSpec.DeletionPolicy.RETAIN);
        when(provider.type()).thenReturn("versity");
        stubResourceGet(invalidUserClient, S3Backend.class, "backend", backend());
        S3User invalidUser = user("alice", "backend", null);
        invalidUser.getSpec().setBackendRef(null);
        stubResourceGet(invalidUserClient, S3User.class, "alice", invalidUser);

        assertThat(reconciler(invalidUserClient, provider).reconcile(bucketWithInvalidUser, mock(Context.class)))
                .isNotNull();
        assertFailure(bucketWithInvalidUser, "InvalidSpec", "S3User spec.backendRef is required");
    }

    @Test
    void missingUserAndMissingCredentialsBecomeRetryableStatuses() {
        KubernetesClient missingUserClient = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        S3Bucket missingUser = bucket("photos", "backend", "alice", null, S3BucketSpec.DeletionPolicy.RETAIN);
        when(provider.type()).thenReturn("versity");
        stubResourceGet(missingUserClient, S3Backend.class, "backend", backend());
        stubResourceGet(missingUserClient, S3User.class, "alice", null);

        assertThat(reconciler(missingUserClient, provider).reconcile(missingUser, mock(Context.class)))
                .isNotNull();
        assertFailure(missingUser, "UserNotFound", "S3User not found: alice");

        KubernetesClient missingSecretClient = mock(KubernetesClient.class);
        S3Bucket missingSecret = bucket("photos", "backend", "alice", null, S3BucketSpec.DeletionPolicy.RETAIN);
        stubResourceGet(missingSecretClient, S3Backend.class, "backend", backend());
        stubResourceGet(missingSecretClient, S3User.class, "alice", user("alice", "backend", null));
        stubSecrets(missingSecretClient, new SecretResult("alice-s3", null));

        assertThat(reconciler(missingSecretClient, provider).reconcile(missingSecret, mock(Context.class)))
                .isNotNull();
        assertFailure(missingSecret, "UserCredentialsNotFound", "User credentials Secret not found: alice-s3");
    }

    @Test
    void providerErrorsBecomeRetryableStatus() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        S3Bucket bucket = bucket("photos", "backend", "alice", null, S3BucketSpec.DeletionPolicy.RETAIN);
        stubDependencies(client, provider, backend(), user("alice", "backend", null), userSecret("alice-s3"), adminSecret());
        doThrow(new S3ProviderException("provider boom"))
                .when(provider)
                .createBucket(ENDPOINT, "admin-access", "admin-secret", "photos", "alice-access");

        assertThat(reconciler(client, provider).reconcile(bucket, mock(Context.class)))
                .isNotNull();

        assertFailure(bucket, "ProviderError", "provider boom");
    }

    @Test
    @SuppressWarnings("unchecked")
    void preparesBackendUserAndSecretEventSources() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        EventSourceContext<S3Bucket> context = mock(EventSourceContext.class);
        when(context.getClient()).thenReturn(client);

        assertThat(reconciler(client, provider).prepareEventSources(context)).hasSize(3);
    }

    @Test
    void cleanupSkipsRetainedOrUnavailableDependencies() {
        VersityS3Provider provider = mock(VersityS3Provider.class);

        KubernetesClient retainedClient = mock(KubernetesClient.class);
        S3Bucket retained = bucket("photos", "backend", "alice", null, S3BucketSpec.DeletionPolicy.RETAIN);
        assertThat(reconciler(retainedClient, provider).cleanup(retained, mock(Context.class)))
                .isNotNull();
        verify(provider, never()).deleteBucket(any(), any(), any(), any());

        KubernetesClient missingBackendClient = mock(KubernetesClient.class);
        S3Bucket deleteMissingBackend = bucket("photos", "backend", "alice", null, S3BucketSpec.DeletionPolicy.DELETE);
        stubResourceGet(missingBackendClient, S3Backend.class, "backend", null);
        assertThat(reconciler(missingBackendClient, provider).cleanup(deleteMissingBackend, mock(Context.class)))
                .isNotNull();

        KubernetesClient unsupportedBackendClient = mock(KubernetesClient.class);
        S3Bucket deleteUnsupported = bucket("photos", "backend", "alice", null, S3BucketSpec.DeletionPolicy.DELETE);
        S3Backend unsupported = backend();
        unsupported.getSpec().setProvider("other");
        stubResourceGet(unsupportedBackendClient, S3Backend.class, "backend", unsupported);
        when(provider.type()).thenReturn("versity");
        assertThat(reconciler(unsupportedBackendClient, provider).cleanup(deleteUnsupported, mock(Context.class)))
                .isNotNull();

        KubernetesClient missingAdminClient = mock(KubernetesClient.class);
        S3Bucket deleteMissingAdmin = bucket("photos", "backend", "alice", null, S3BucketSpec.DeletionPolicy.DELETE);
        stubResourceGet(missingAdminClient, S3Backend.class, "backend", backend());
        stubSecrets(missingAdminClient, new SecretResult("admin", null));
        assertThat(reconciler(missingAdminClient, provider).cleanup(deleteMissingAdmin, mock(Context.class)))
                .isNotNull();
    }

    @Test
    void deleteCleanupDeletesConfiguredProviderBucket() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        S3Bucket bucket =
                bucket("resource-name", "backend", "alice", "actual-bucket", S3BucketSpec.DeletionPolicy.DELETE);
        when(provider.type()).thenReturn("versity");
        stubResourceGet(client, S3Backend.class, "backend", backend());
        stubSecrets(client, new SecretResult("admin", adminSecret()));

        var control = reconciler(client, provider).cleanup(bucket, mock(Context.class));

        assertThat(control).isNotNull();
        verify(provider).deleteBucket(ENDPOINT, "admin-access", "admin-secret", "actual-bucket");
    }

    private static S3BucketReconciler reconciler(KubernetesClient client, VersityS3Provider provider) {
        S3BucketReconciler reconciler = new S3BucketReconciler();
        reconciler.client = client;
        reconciler.provider = provider;
        reconciler.resyncInterval = Duration.ofMinutes(1);
        reconciler.retryDelay = Duration.ofSeconds(5);
        return reconciler;
    }

    private static void stubDependencies(
            KubernetesClient client,
            VersityS3Provider provider,
            S3Backend backend,
            S3User user,
            Secret userSecret,
            Secret adminSecret) {
        when(provider.type()).thenReturn("versity");
        stubResourceGet(client, S3Backend.class, "backend", backend);
        stubResourceGet(client, S3User.class, "alice", user);
        stubSecrets(
                client,
                new SecretResult(userSecret.getMetadata().getName(), userSecret),
                new SecretResult("admin", adminSecret));
    }

    private static void assertFailure(S3Bucket bucket, String reason, String message) {
        assertThat(bucket.getStatus().getConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.getStatus()).isEqualTo("False");
            assertThat(condition.getReason()).isEqualTo(reason);
            assertThat(condition.getMessage()).isEqualTo(message);
        });
    }
}
