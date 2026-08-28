package com.dajudge.s3operator.reconciler;

import static com.dajudge.s3operator.reconciler.Fabric8TestMocks.stubResourceGet;
import static com.dajudge.s3operator.reconciler.Fabric8TestMocks.stubSecrets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

class ReconcilerSupportTestCleanupDependencies {
    private static final String NS = "ns";

    @Test
    void returnsUsableBackendAndAdminSecret() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        S3Backend backend = backend("versity");
        Secret admin = new SecretBuilder()
                .withNewMetadata()
                .withName("admin")
                .endMetadata()
                .build();
        when(provider.type()).thenReturn("versity");
        stubResourceGet(client, S3Backend.class, "backend", backend);
        stubSecrets(client, new SecretResult("admin", admin));

        var result = ReconcilerSupport.cleanupDependencies(client, provider, NS, "backend");

        assertThat(result).isNotNull();
        assertThat(result.backend()).isSameAs(backend);
        assertThat(result.adminSecret()).isSameAs(admin);
    }

    @Test
    void returnsNullWhenBackendCannotBeUsed() {
        VersityS3Provider provider = mock(VersityS3Provider.class);
        when(provider.type()).thenReturn("versity");

        KubernetesClient missingClient = mock(KubernetesClient.class);
        stubResourceGet(missingClient, S3Backend.class, "backend", null);
        assertThat(ReconcilerSupport.cleanupDependencies(missingClient, provider, NS, "backend"))
                .isNull();

        KubernetesClient unsupportedClient = mock(KubernetesClient.class);
        stubResourceGet(unsupportedClient, S3Backend.class, "backend", backend("other"));
        assertThat(ReconcilerSupport.cleanupDependencies(unsupportedClient, provider, NS, "backend"))
                .isNull();
    }

    @Test
    void returnsNullWhenAdminSecretIsMissing() {
        KubernetesClient client = mock(KubernetesClient.class);
        VersityS3Provider provider = mock(VersityS3Provider.class);
        when(provider.type()).thenReturn("versity");
        stubResourceGet(client, S3Backend.class, "backend", backend("versity"));
        stubSecrets(client, new SecretResult("admin", null));

        assertThat(ReconcilerSupport.cleanupDependencies(client, provider, NS, "backend"))
                .isNull();
    }

    private static S3Backend backend(String provider) {
        LocalObjectReference ref = new LocalObjectReference();
        ref.setName("admin");
        S3BackendSpec spec = new S3BackendSpec();
        spec.setProvider(provider);
        spec.setEndpoint("http://versity:7070");
        spec.setAdminCredentialsSecretRef(ref);
        S3Backend backend = new S3Backend();
        backend.setSpec(spec);
        return backend;
    }
}
