package com.dajudge.s3operator.reconciler;

import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.ADMIN_CREDENTIALS_NOT_FOUND;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.BACKEND_NOT_FOUND;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.INVALID_CREDENTIALS_SECRET;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.UNSUPPORTED_PROVIDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconcilerSupportTest {

    @Test
    void requiresExistingBackendWithSupportedProvider() {
        S3Backend backend = backend("versity", "admin");
        KubernetesClient client = clientReturningBackend("backend", backend);

        assertThat(ReconcilerSupport.requireBackend(client, new VersityS3Provider(), "ns", "backend"))
                .isSameAs(backend);
    }

    @Test
    void rejectsMissingAndUnsupportedBackends() {
        KubernetesClient missingClient = clientReturningBackend("missing", null);
        assertThatThrownBy(
                        () -> ReconcilerSupport.requireBackend(missingClient, new VersityS3Provider(), "ns", "missing"))
                .isInstanceOfSatisfying(ReconciliationException.class, error -> {
                    assertThat(error.reason()).isEqualTo(BACKEND_NOT_FOUND);
                    assertThat(error).hasMessage("S3Backend not found: missing");
                });

        KubernetesClient unsupportedClient = clientReturningBackend("unsupported", backend("other", "admin"));
        assertThatThrownBy(() -> ReconcilerSupport.requireBackend(
                        unsupportedClient, new VersityS3Provider(), "ns", "unsupported"))
                .isInstanceOfSatisfying(ReconciliationException.class, error -> {
                    assertThat(error.reason()).isEqualTo(UNSUPPORTED_PROVIDER);
                    assertThat(error).hasMessage("Unsupported S3 provider: other");
                });
    }

    @Test
    void requiresReferencedAdminSecret() {
        S3Backend backend = backend("versity", "admin");
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName("admin")
                .endMetadata()
                .build();
        KubernetesClient client = clientReturningSecret("admin", secret);

        assertThat(ReconcilerSupport.requireAdminSecret(client, "ns", backend)).isSameAs(secret);

        KubernetesClient missingClient = clientReturningSecret("admin", null);
        assertThatThrownBy(() -> ReconcilerSupport.requireAdminSecret(missingClient, "ns", backend))
                .isInstanceOfSatisfying(ReconciliationException.class, error -> {
                    assertThat(error.reason()).isEqualTo(ADMIN_CREDENTIALS_NOT_FOUND);
                    assertThat(error).hasMessage("Admin credentials Secret not found");
                });
    }

    @Test
    void buildsReadyConditionAndPreservesTransitionTime() {
        String existing = "2026-08-28T10:00:00Z";
        var previous = new ConditionBuilder()
                .withType("Ready")
                .withStatus("True")
                .withReason("Reconciled")
                .withLastTransitionTime(existing)
                .build();

        var condition = ReconcilerSupport.readyCondition(7L, List.of(previous), "True", "Reconciled", null);

        assertThat(condition.getType()).isEqualTo("Ready");
        assertThat(condition.getStatus()).isEqualTo("True");
        assertThat(condition.getReason()).isEqualTo("Reconciled");
        assertThat(condition.getMessage()).isEqualTo("Reconciled");
        assertThat(condition.getObservedGeneration()).isEqualTo(7L);
        assertThat(condition.getLastTransitionTime()).isEqualTo(existing);
    }

    @Test
    void usesExplicitReadyConditionMessage() {
        var condition = ReconcilerSupport.readyCondition(7L, null, "False", "ProviderError", "provider failed");

        assertThat(condition.getMessage()).isEqualTo("provider failed");
        assertThat(condition.getLastTransitionTime()).isNotBlank();
    }

    @Test
    void defaultsConfiguredNamesOnlyWhenAbsentOrBlank() {
        assertThat(ReconcilerSupport.defaultedName(null, "fallback")).isEqualTo("fallback");
        assertThat(ReconcilerSupport.defaultedName("", "fallback")).isEqualTo("fallback");
        assertThat(ReconcilerSupport.defaultedName("  ", "fallback")).isEqualTo("fallback");
        assertThat(ReconcilerSupport.defaultedName("configured", "fallback")).isEqualTo("configured");
    }

    @Test
    void reusesTransitionTimeOnlyForSameReadyState() {
        String existing = "2026-08-28T10:00:00Z";
        var ready = new ConditionBuilder()
                .withType("Ready")
                .withStatus("True")
                .withReason("Reconciled")
                .withLastTransitionTime(existing)
                .build();

        assertThat(ReconcilerSupport.transitionTime(List.of(ready), "True", "Reconciled"))
                .isEqualTo(existing);

        assertFreshTransition(ReconcilerSupport.transitionTime(null, "True", "Reconciled"), existing);
        assertFreshTransition(
                ReconcilerSupport.transitionTime(
                        List.of(new ConditionBuilder(ready).withType("Other").build()), "True", "Reconciled"),
                existing);
        assertFreshTransition(ReconcilerSupport.transitionTime(List.of(ready), "False", "Reconciled"), existing);
        assertFreshTransition(ReconcilerSupport.transitionTime(List.of(ready), "True", "ProviderError"), existing);
        assertFreshTransition(
                ReconcilerSupport.transitionTime(
                        List.of(new ConditionBuilder(ready)
                                .withLastTransitionTime(null)
                                .build()),
                        "True",
                        "Reconciled"),
                existing);
    }

    @Test
    void readsEncodedDataBeforeStringDataAndRejectsMissingKeys() {
        String encoded = Base64.getEncoder().encodeToString("encoded-value".getBytes(StandardCharsets.UTF_8));
        Secret secret = new SecretBuilder()
                .withMetadata(new ObjectMetaBuilder().withName("credentials").build())
                .addToData("key", encoded)
                .addToStringData("key", "string-value")
                .addToStringData("string-only", "plain-value")
                .build();

        assertThat(ReconcilerSupport.secretValue(secret, "key")).isEqualTo("encoded-value");
        assertThat(ReconcilerSupport.secretValue(secret, "string-only")).isEqualTo("plain-value");
        assertThatThrownBy(() -> ReconcilerSupport.secretValue(secret, "missing"))
                .isInstanceOfSatisfying(ReconciliationException.class, error -> {
                    assertThat(error.reason()).isEqualTo(INVALID_CREDENTIALS_SECRET);
                    assertThat(error).hasMessage("Missing key 'missing' in Secret credentials");
                });
    }

    private static KubernetesClient clientReturningBackend(String name, S3Backend backend) {
        KubernetesClient client = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<S3Backend, KubernetesResourceList<S3Backend>, Resource<S3Backend>> operation =
                mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<S3Backend, KubernetesResourceList<S3Backend>, Resource<S3Backend>> namespaced =
                mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        Resource<S3Backend> resource = mock(Resource.class);

        when(client.resources(S3Backend.class)).thenReturn(operation);
        when(operation.inNamespace("ns")).thenReturn(namespaced);
        when(namespaced.withName(name)).thenReturn(resource);
        when(resource.get()).thenReturn(backend);
        return client;
    }

    private static KubernetesClient clientReturningSecret(String name, Secret secret) {
        KubernetesClient client = mock(KubernetesClient.class);
        @SuppressWarnings("unchecked")
        MixedOperation<Secret, SecretList, Resource<Secret>> operation = mock(MixedOperation.class);
        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Secret, SecretList, Resource<Secret>> namespaced = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        Resource<Secret> resource = mock(Resource.class);

        when(client.secrets()).thenReturn(operation);
        when(operation.inNamespace("ns")).thenReturn(namespaced);
        when(namespaced.withName(name)).thenReturn(resource);
        when(resource.get()).thenReturn(secret);
        return client;
    }

    private static void assertFreshTransition(String actual, String existing) {
        assertThat(actual).isNotEqualTo(existing);
        assertThat(Instant.parse(actual)).isNotNull();
    }

    private static S3Backend backend(String provider, String adminSecretName) {
        LocalObjectReference secretRef = new LocalObjectReference();
        secretRef.setName(adminSecretName);
        S3BackendSpec spec = new S3BackendSpec();
        spec.setProvider(provider);
        spec.setEndpoint("http://versity:7070");
        spec.setAdminCredentialsSecretRef(secretRef);
        S3Backend backend = new S3Backend();
        backend.setSpec(spec);
        return backend;
    }
}
