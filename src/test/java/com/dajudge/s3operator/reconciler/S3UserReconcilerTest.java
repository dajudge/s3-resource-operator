package com.dajudge.s3operator.reconciler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import com.dajudge.s3operator.api.S3UserStatus;
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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class S3UserReconcilerTest {
    private static final String NS = "ns";
    private static final String ENDPOINT = "http://versity:7070";

    @Test
    void reconcilesExistingCredentialsAndPublishesReadyStatus() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        S3User user = user("alice", "backend", null);
        stubDependencies(
                client,
                provider,
                backend(),
                secret("admin", "admin-access", "admin-secret"),
                "alice-s3",
                secret("alice-s3", "alice-access", "alice-secret"));

        var control = reconciler(client, provider).reconcile(user, mock(Context.class));

        assertThat(control).isNotNull();
        verify(provider).createUser(ENDPOINT, "admin-access", "admin-secret", "alice-access", "alice-secret", "user");
        assertThat(user.getStatus().getAccessKeyId()).isEqualTo("alice-access");
        assertThat(user.getStatus().getSecretName()).isEqualTo("alice-s3");
        assertThat(user.getStatus().getObservedGeneration()).isEqualTo(7L);
        assertThat(user.getStatus().getConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.getType()).isEqualTo("Ready");
            assertThat(condition.getStatus()).isEqualTo("True");
            assertThat(condition.getReason()).isEqualTo("Reconciled");
            assertThat(condition.getMessage()).isEqualTo("Versity user and credentials are ready");
            assertThat(condition.getObservedGeneration()).isEqualTo(7L);
            assertThat(condition.getLastTransitionTime()).isNotBlank();
        });
    }

    @Test
    void usesConfiguredSecretNameAndCreatesRandomCredentialsWhenMissing() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        S3User user = user("alice", "backend", "custom-credentials");
        when(provider.type()).thenReturn("versity");
        stubResourceGet(client, S3Backend.class, "backend", backend());
        MixedOperation<Secret, SecretList, Resource<Secret>> secrets = stubSecrets(
                client,
                new SecretResult("admin", secret("admin", "admin-access", "admin-secret")),
                new SecretResult("custom-credentials", null));
        AtomicReference<Secret> createdSecret = new AtomicReference<>();
        when(secrets.resource(any(Secret.class))).thenAnswer(invocation -> {
            Secret created = invocation.getArgument(0);
            createdSecret.set(created);
            @SuppressWarnings("unchecked")
            Resource<Secret> resource = mock(Resource.class);
            when(resource.create()).thenReturn(created);
            return resource;
        });

        var control = reconciler(client, provider).reconcile(user, mock(Context.class));

        assertThat(control).isNotNull();
        assertThat(user.getStatus().getSecretName()).isEqualTo("custom-credentials");
        String generatedSecret = createdSecret.get().getStringData().get("secretKey");
        String zeroSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        assertThat(generatedSecret).hasSize(43).isNotEqualTo(zeroSecret);
        verify(provider)
                .createUser(
                        eq(ENDPOINT),
                        eq("admin-access"),
                        eq("admin-secret"),
                        eq("ns.alice"),
                        argThat(generatedSecret::equals),
                        eq("user"));
    }

    @Test
    void translatesProviderErrorsIntoRetryableStatus() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        S3User user = user("alice", "backend", null);
        stubDependencies(
                client,
                provider,
                backend(),
                secret("admin", "admin-access", "admin-secret"),
                "alice-s3",
                secret("alice-s3", "alice-access", "alice-secret"));
        doThrow(new S3ProviderException("provider boom"))
                .when(provider)
                .createUser(ENDPOINT, "admin-access", "admin-secret", "alice-access", "alice-secret", "user");

        var control = reconciler(client, provider).reconcile(user, mock(Context.class));

        assertThat(control).isNotNull();
        assertThat(user.getStatus().getConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.getStatus()).isEqualTo("False");
            assertThat(condition.getReason()).isEqualTo("ProviderError");
            assertThat(condition.getMessage()).isEqualTo("provider boom");
        });
    }

    @Test
    void invalidSpecsBecomeRetryableStatus() {
        KubernetesClient invalidUserClient = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        S3User invalidUser = user("alice", null, null);

        var invalidUserControl = reconciler(invalidUserClient, provider).reconcile(invalidUser, mock(Context.class));

        assertThat(invalidUserControl).isNotNull();
        verify(provider, never()).createUser(any(), any(), any(), any(), any(), any());
        assertThat(invalidUser.getStatus().getConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.getStatus()).isEqualTo("False");
            assertThat(condition.getReason()).isEqualTo("InvalidSpec");
            assertThat(condition.getMessage()).isEqualTo("S3User spec.backendRef is required");
        });

        KubernetesClient invalidBackendClient = mock(KubernetesClient.class);
        S3User validUser = user("alice", "backend", null);
        S3Backend invalidBackend = new S3Backend();
        invalidBackend.setSpec(new S3BackendSpec());
        stubResourceGet(invalidBackendClient, S3Backend.class, "backend", invalidBackend);

        var invalidBackendControl = reconciler(invalidBackendClient, provider).reconcile(validUser, mock(Context.class));

        assertThat(invalidBackendControl).isNotNull();
        assertThat(validUser.getStatus().getConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.getStatus()).isEqualTo("False");
            assertThat(condition.getReason()).isEqualTo("InvalidSpec");
            assertThat(condition.getMessage()).isEqualTo("S3Backend spec.endpoint is required");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void preparesBackendAndSecretEventSources() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        EventSourceContext<S3User> context = mock(EventSourceContext.class);
        when(context.getClient()).thenReturn(client);

        assertThat(reconciler(client, provider).prepareEventSources(context)).hasSize(2);
    }

    @Test
    void cleanupRejectsReferencedUser() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        S3User user = user("alice", "backend", null);
        S3Bucket bucket = bucketReferencing("alice");
        stubResourceList(client, S3Bucket.class, List.of(bucket));

        assertThatThrownBy(() -> reconciler(client, provider).cleanup(user, mock(Context.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("S3User is still referenced by an S3Bucket: alice");
        verify(provider, never()).deleteUser(any(), any(), any(), any());
    }

    @Test
    void cleanupSkipsWhenDependenciesCannotSupportDeletion() {
        VersityS3Provider provider = mock(VersityS3Provider.class);

        KubernetesClient invalidUserClient = mock(KubernetesClient.class);
        stubResourceList(invalidUserClient, S3Bucket.class, List.of());
        assertThat(reconciler(invalidUserClient, provider).cleanup(user("alice", null, null), mock(Context.class)))
                .isNotNull();

        KubernetesClient missingBackendClient = mock(KubernetesClient.class);
        stubResourceList(missingBackendClient, S3Bucket.class, List.of());
        stubResourceGet(missingBackendClient, S3Backend.class, "backend", null);
        assertThat(reconciler(missingBackendClient, provider).cleanup(user("alice", "backend", null), mock(Context.class)))
                .isNotNull();

        KubernetesClient unsupportedBackendClient = mock(KubernetesClient.class);
        stubResourceList(unsupportedBackendClient, S3Bucket.class, List.of());
        S3Backend unsupported = backend();
        unsupported.getSpec().setProvider("other");
        stubResourceGet(unsupportedBackendClient, S3Backend.class, "backend", unsupported);
        when(provider.type()).thenReturn("versity");
        assertThat(
                        reconciler(unsupportedBackendClient, provider)
                                .cleanup(user("alice", "backend", null), mock(Context.class)))
                .isNotNull();

        KubernetesClient missingAdminClient = mock(KubernetesClient.class);
        stubResourceList(missingAdminClient, S3Bucket.class, List.of());
        stubResourceGet(missingAdminClient, S3Backend.class, "backend", backend());
        stubSecrets(missingAdminClient, new SecretResult("admin", null));
        assertThat(reconciler(missingAdminClient, provider).cleanup(user("alice", "backend", null), mock(Context.class)))
                .isNotNull();
    }

    @Test
    void cleanupDeletesProviderUserUsingStatusFallback() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        S3User user = user("alice", "backend", null);
        S3UserStatus status = new S3UserStatus();
        status.setAccessKeyId("status-access");
        user.setStatus(status);
        stubResourceList(client, S3Bucket.class, List.of(bucketReferencing("bob")));
        when(provider.type()).thenReturn("versity");
        stubResourceGet(client, S3Backend.class, "backend", backend());
        stubSecrets(
                client,
                new SecretResult("admin", secret("admin", "admin-access", "admin-secret")),
                new SecretResult("alice-s3", null));

        var control = reconciler(client, provider).cleanup(user, mock(Context.class));

        assertThat(control).isNotNull();
        verify(provider).deleteUser(ENDPOINT, "admin-access", "admin-secret", "status-access");
    }

    private static S3UserReconciler reconciler(KubernetesClient client, VersityS3Provider provider) {
        S3UserReconciler reconciler = new S3UserReconciler();
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
            Secret admin,
            String credentialsName,
            Secret credentials) {
        when(provider.type()).thenReturn("versity");
        stubResourceGet(client, S3Backend.class, "backend", backend);
        stubSecrets(client, new SecretResult("admin", admin), new SecretResult(credentialsName, credentials));
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
    private static <T extends HasMetadata> void stubResourceList(
            KubernetesClient client, Class<T> type, List<T> items) {
        MixedOperation<T, KubernetesResourceList<T>, Resource<T>> operation = mock(MixedOperation.class);
        NonNamespaceOperation<T, KubernetesResourceList<T>, Resource<T>> namespaced = mock(NonNamespaceOperation.class);
        KubernetesResourceList<T> list = mock(KubernetesResourceList.class);
        when(client.resources(type)).thenReturn(operation);
        when(operation.inNamespace(NS)).thenReturn(namespaced);
        when(namespaced.list()).thenReturn(list);
        when(list.getItems()).thenReturn(items);
    }

    @SuppressWarnings("unchecked")
    private static MixedOperation<Secret, SecretList, Resource<Secret>> stubSecrets(
            KubernetesClient client, SecretResult... results) {
        MixedOperation<Secret, SecretList, Resource<Secret>> operation = mock(MixedOperation.class);
        NonNamespaceOperation<Secret, SecretList, Resource<Secret>> namespaced = mock(NonNamespaceOperation.class);
        when(client.secrets()).thenReturn(operation);
        when(operation.inNamespace(NS)).thenReturn(namespaced);
        for (SecretResult result : results) {
            Resource<Secret> resource = mock(Resource.class);
            when(namespaced.withName(result.name())).thenReturn(resource);
            when(resource.get()).thenReturn(result.secret());
        }
        return operation;
    }

    private static S3User user(String name, String backendRef, String secretName) {
        S3UserSpec spec = new S3UserSpec();
        spec.setBackendRef(backendRef);
        spec.setSecretName(secretName);
        S3User user = new S3User();
        user.setApiVersion("s3.dajudge.com/v1alpha1");
        user.setKind("S3User");
        user.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(NS)
                .withUid("uid")
                .withGeneration(7L)
                .build());
        user.setSpec(spec);
        return user;
    }

    private static S3Bucket bucketReferencing(String userName) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef("backend");
        spec.setUserRef(userName);
        S3Bucket bucket = new S3Bucket();
        bucket.setSpec(spec);
        return bucket;
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
