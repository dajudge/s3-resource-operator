package com.dajudge.s3operator.reconciler;

import static com.dajudge.s3operator.reconciler.Fabric8TestMocks.stubResourceGet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserSpec;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RelatedResourceEventSourcesTest {
    private static final String NS = "ns";

    @Test
    @SuppressWarnings("unchecked")
    void createsExpectedEventSources() {
        KubernetesClient client = mock(KubernetesClient.class);
        EventSourceContext<S3User> userContext = mock(EventSourceContext.class);
        EventSourceContext<S3Bucket> bucketContext = mock(EventSourceContext.class);

        assertThat(RelatedResourceEventSources.forUser(client, userContext)).hasSize(2);
        assertThat(RelatedResourceEventSources.forBucket(client, bucketContext)).hasSize(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void matchingReturnsOnlyPrimaryResourcesAcceptedByPredicate() {
        EventSourceContext<S3User> context = mock(EventSourceContext.class, RETURNS_DEEP_STUBS);
        S3User alice = user("alice", NS, null);
        S3User bob = user("bob", NS, null);
        when(context.getPrimaryCache().list()).thenReturn(Stream.of(alice, bob));

        Set<ResourceID> matches = RelatedResourceEventSources.matching(
                context, candidate -> "alice".equals(candidate.getMetadata().getName()));

        assertThat(matches).containsExactly(ResourceID.fromResource(alice));
    }

    @Test
    void userSecretMatchesDefaultAndConfiguredCredentialNames() {
        KubernetesClient client = mock(KubernetesClient.class);
        S3User defaultUser = user("alice", NS, null);
        S3User configuredUser = user("alice", NS, "credentials");

        assertThat(RelatedResourceEventSources.secretAffectsUser(client, secret("alice-s3", NS), defaultUser))
                .isTrue();
        assertThat(RelatedResourceEventSources.secretAffectsUser(client, secret("credentials", NS), configuredUser))
                .isTrue();
        assertThat(RelatedResourceEventSources.userSecretName(defaultUser)).isEqualTo("alice-s3");
        assertThat(RelatedResourceEventSources.userSecretName(configuredUser)).isEqualTo("credentials");
    }

    @Test
    void userSecretRejectsOtherNamespaceAndUnrelatedSecrets() {
        KubernetesClient client = mock(KubernetesClient.class);
        S3User user = user("alice", NS, null);

        assertThat(RelatedResourceEventSources.secretAffectsUser(client, secret("alice-s3", "other"), user))
                .isFalse();

        stubResourceGet(client, S3Backend.class, "backend", backend("admin"));
        assertThat(RelatedResourceEventSources.secretAffectsUser(client, secret("unrelated", NS), user))
                .isFalse();
    }

    @Test
    void userSecretMatchesBackendAdminSecret() {
        KubernetesClient client = mock(KubernetesClient.class);
        S3User user = user("alice", NS, null);
        stubResourceGet(client, S3Backend.class, "backend", backend("admin"));

        assertThat(RelatedResourceEventSources.secretAffectsUser(client, secret("admin", NS), user))
                .isTrue();
    }

    @Test
    void bucketSecretMatchesUserCredentialsBeforeBackendCredentials() {
        KubernetesClient client = mock(KubernetesClient.class);
        S3Bucket bucket = bucket("photos", NS);
        stubResourceGet(client, S3User.class, "alice", user("alice", NS, "custom-user-secret"));

        assertThat(RelatedResourceEventSources.secretAffectsBucket(
                        client, secret("custom-user-secret", NS), bucket))
                .isTrue();
    }

    @Test
    void bucketSecretFallsBackToBackendAdminSecret() {
        KubernetesClient client = mock(KubernetesClient.class);
        S3Bucket bucket = bucket("photos", NS);
        stubResourceGet(client, S3User.class, "alice", user("alice", NS, null));
        stubResourceGet(client, S3Backend.class, "backend", backend("admin"));

        assertThat(RelatedResourceEventSources.secretAffectsBucket(client, secret("admin", NS), bucket))
                .isTrue();
        assertThat(RelatedResourceEventSources.secretAffectsBucket(client, secret("unrelated", NS), bucket))
                .isFalse();
    }

    @Test
    void bucketSecretRejectsOtherNamespace() {
        KubernetesClient client = mock(KubernetesClient.class);
        S3Bucket bucket = bucket("photos", NS);

        assertThat(RelatedResourceEventSources.secretAffectsBucket(client, secret("admin", "other"), bucket))
                .isFalse();
    }

    @Test
    void namespaceComparisonRequiresEquality() {
        assertThat(RelatedResourceEventSources.sameNamespace(user("alice", NS, null), secret("x", NS)))
                .isTrue();
        assertThat(RelatedResourceEventSources.sameNamespace(user("alice", NS, null), secret("x", "other")))
                .isFalse();
    }

    private static S3Backend backend(String adminSecretName) {
        LocalObjectReference ref = new LocalObjectReference();
        ref.setName(adminSecretName);
        S3BackendSpec spec = new S3BackendSpec();
        spec.setProvider("versity");
        spec.setEndpoint("http://versity:7070");
        spec.setAdminCredentialsSecretRef(ref);
        S3Backend backend = new S3Backend();
        backend.setMetadata(new ObjectMetaBuilder().withName("backend").withNamespace(NS).build());
        backend.setSpec(spec);
        return backend;
    }

    private static S3User user(String name, String namespace, String secretName) {
        S3UserSpec spec = new S3UserSpec();
        spec.setBackendRef("backend");
        spec.setSecretName(secretName);
        S3User user = new S3User();
        user.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(namespace).build());
        user.setSpec(spec);
        return user;
    }

    private static S3Bucket bucket(String name, String namespace) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef("backend");
        spec.setUserRef("alice");
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(namespace).build());
        bucket.setSpec(spec);
        return bucket;
    }

    private static Secret secret(String name, String namespace) {
        return new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .build();
    }
}
