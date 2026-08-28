package com.dajudge.s3operator.reconciler;

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
import com.dajudge.s3operator.api.S3UserSpec;
import com.dajudge.s3operator.provider.S3ProviderException;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class S3BucketReconcilerTest {
    private static final String NS = "ns";
    private static final String ENDPOINT = "http://versity:7070";

    @Test
    void reconcilesBucketAndPublishesReadyStatus() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        S3Bucket bucket = bucket("photos", "backend", "alice", null, S3BucketSpec.DeletionPolicy.RETAIN);
        stubDependencies(client, provider, backend(), user("alice", null), userSecret("alice-s3"), adminSecret());

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
                user("alice", "custom-secret"),
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
        S3User invalidUser = user("alice", null);
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
        stubResourceGet(missingSecretClient, S3User.class, "alice", user("alice", null));
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
        stubDependencies(client, provider, backend(), user("alice", null), userSecret("alice-s3"), adminSecret());
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

    @SuppressWarnings("unchecked")
    private static <T extends HasMetadata> void stubResourceGet(
            KubernetesClient client, Class<T> type, String name, T value) {
        MixedOperation<T, KubernetesResourceList<T>, Resource<T>> operation = mock(MixedOperation.class);
        NonNamespaceOperation<T, KubernetesResourceList<T>, Resource<T>> namespaced = mock(NonNamespaceOperation.class);
        Resource<T> resource = mock(Resource.class);
        when(client.resources(type)).thenReturn(operation);
        when(operation.inNamespace(NS)).thenReturn(namespaced);
        when(namespaced.withName(name)).thenReturn(resource);
        when(resource.get()).thenReturn(value);
    }

    @SuppressWarnings("unchecked")
    private static void stubSecrets(KubernetesClient client, SecretResult... results) {
        MixedOperation<Secret, SecretList, Resource<Secret>> operation = mock(MixedOperation.class);
        NonNamespaceOperation<Secret, SecretList, Resource<Secret>> namespaced = mock(NonNamespaceOperation.class);
        when(client.secrets()).thenReturn(operation);
        when(operation.inNamespace(NS)).thenReturn(namespaced);
        for (SecretResult result : results) {
            Resource<Secret> resource = mock(Resource.class);
            when(namespaced.withName(result.name())).thenReturn(resource);
            when(resource.get()).thenReturn(result.secret());
        }
    }

    private static void assertFailure(S3Bucket bucket, String reason, String message) {
        assertThat(bucket.getStatus().getConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.getStatus()).isEqualTo("False");
            assertThat(condition.getReason()).isEqualTo(reason);
            assertThat(condition.getMessage()).isEqualTo(message);
        });
    }

    private static S3Bucket bucket(
            String name,
            String backendRef,
            String userRef,
            String bucketName,
            S3BucketSpec.DeletionPolicy deletionPolicy) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef(backendRef);
        spec.setUserRef(userRef);
        spec.setBucketName(bucketName);
        spec.setDeletionPolicy(deletionPolicy);
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(NS)
                .withGeneration(9L)
                .build());
        bucket.setSpec(spec);
        return bucket;
    }

    private static S3User user(String name, String secretName) {
        S3UserSpec spec = new S3UserSpec();
        spec.setBackendRef("backend");
        spec.setSecretName(secretName);
        S3User user = new S3User();
        user.setMetadata(
                new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
        user.setSpec(spec);
        return user;
    }

    private static S3Backend backend() {
        LocalObjectReference ref = new LocalObjectReference();
        ref.setName("admin");
        S3BackendSpec spec = new S3BackendSpec();
        spec.setProvider("versity");
        spec.setEndpoint(ENDPOINT);
        spec.setAdminCredentialsSecretRef(ref);
        S3Backend backend = new S3Backend();
        backend.setSpec(spec);
        return backend;
    }

    private static Secret userSecret(String name) {
        return secret(name, "alice-access", "alice-secret");
    }

    private static Secret adminSecret() {
        return secret("admin", "admin-access", "admin-secret");
    }

    private static Secret secret(String name, String accessKey, String secretKey) {
        return new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .endMetadata()
                .addToStringData("accessKey", accessKey)
                .addToStringData("secretKey", secretKey)
                .build();
    }

    private record SecretResult(String name, Secret secret) {}
}
