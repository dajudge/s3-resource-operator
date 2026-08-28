package com.dajudge.s3operator.reconciler;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3User;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

final class RelatedResourceEventSources {
    private RelatedResourceEventSources() {}

    static <S extends HasMetadata, P extends HasMetadata> InformerEventSource<S, P> informer(
            Class<S> secondaryType,
            Class<P> primaryType,
            EventSourceContext<P> context,
            Function<S, Set<ResourceID>> mapper) {
        var configuration = InformerEventSourceConfiguration.from(secondaryType, primaryType)
                .withNamespacesInheritedFromController()
                .withSecondaryToPrimaryMapper(mapper::apply)
                .build();
        return new InformerEventSource<>(configuration, context);
    }

    static <P extends HasMetadata> Set<ResourceID> matching(EventSourceContext<P> context, Predicate<P> predicate) {
        return context.getPrimaryCache()
                .list()
                .filter(predicate)
                .map(ResourceID::fromResource)
                .collect(Collectors.toSet());
    }

    static List<EventSource<?, S3User>> forUser(KubernetesClient client, EventSourceContext<S3User> context) {
        var backends = informer(S3Backend.class, S3User.class, context, userBackendMapper(context));
        var secrets = informer(Secret.class, S3User.class, context, userSecretMapper(client, context));
        return List.of(backends, secrets);
    }

    static List<EventSource<?, S3Bucket>> forBucket(KubernetesClient client, EventSourceContext<S3Bucket> context) {
        var backends = informer(S3Backend.class, S3Bucket.class, context, bucketBackendMapper(context));
        var users = informer(S3User.class, S3Bucket.class, context, bucketUserMapper(context));
        var secrets = informer(Secret.class, S3Bucket.class, context, bucketSecretMapper(client, context));
        return List.of(backends, users, secrets);
    }

    static Function<S3Backend, Set<ResourceID>> userBackendMapper(EventSourceContext<S3User> context) {
        return backend -> matching(
                context,
                user -> ResourceValidation.hasUsableUserSpec(user)
                        && sameNamespace(user, backend)
                        && backend.getMetadata().getName().equals(user.getSpec().getBackendRef()));
    }

    static Function<Secret, Set<ResourceID>> userSecretMapper(
            KubernetesClient client, EventSourceContext<S3User> context) {
        return secret -> matching(
                context, user -> ResourceValidation.hasUsableUserSpec(user) && secretAffectsUser(client, secret, user));
    }

    static Function<S3Backend, Set<ResourceID>> bucketBackendMapper(EventSourceContext<S3Bucket> context) {
        return backend -> matching(
                context,
                bucket -> ResourceValidation.hasUsableBucketSpec(bucket)
                        && sameNamespace(bucket, backend)
                        && backend.getMetadata()
                                .getName()
                                .equals(bucket.getSpec().getBackendRef()));
    }

    static Function<S3User, Set<ResourceID>> bucketUserMapper(EventSourceContext<S3Bucket> context) {
        return user -> matching(
                context,
                bucket -> ResourceValidation.hasUsableBucketSpec(bucket)
                        && sameNamespace(bucket, user)
                        && user.getMetadata().getName().equals(bucket.getSpec().getUserRef()));
    }

    static Function<Secret, Set<ResourceID>> bucketSecretMapper(
            KubernetesClient client, EventSourceContext<S3Bucket> context) {
        return secret -> matching(
                context,
                bucket ->
                        ResourceValidation.hasUsableBucketSpec(bucket) && secretAffectsBucket(client, secret, bucket));
    }

    static boolean secretAffectsUser(KubernetesClient client, Secret secret, S3User user) {
        if (!sameNamespace(user, secret)) return false;
        if (secret.getMetadata().getName().equals(userSecretName(user))) return true;
        S3Backend backend = client.resources(S3Backend.class)
                .inNamespace(user.getMetadata().getNamespace())
                .withName(user.getSpec().getBackendRef())
                .get();
        return ResourceValidation.hasUsableBackendSpec(backend)
                && secret.getMetadata()
                        .getName()
                        .equals(backend.getSpec().getAdminCredentialsSecretRef().getName());
    }

    static boolean secretAffectsBucket(KubernetesClient client, Secret secret, S3Bucket bucket) {
        if (!sameNamespace(bucket, secret)) return false;
        String namespace = bucket.getMetadata().getNamespace();
        S3User user = client.resources(S3User.class)
                .inNamespace(namespace)
                .withName(bucket.getSpec().getUserRef())
                .get();
        if (ResourceValidation.hasUsableUserSpec(user)
                && secret.getMetadata().getName().equals(userSecretName(user))) return true;
        S3Backend backend = client.resources(S3Backend.class)
                .inNamespace(namespace)
                .withName(bucket.getSpec().getBackendRef())
                .get();
        return ResourceValidation.hasUsableBackendSpec(backend)
                && secret.getMetadata()
                        .getName()
                        .equals(backend.getSpec().getAdminCredentialsSecretRef().getName());
    }

    static boolean sameNamespace(HasMetadata primary, HasMetadata secondary) {
        return primary.getMetadata()
                .getNamespace()
                .equals(secondary.getMetadata().getNamespace());
    }

    static String userSecretName(S3User user) {
        return user.getSpec().getSecretName() == null
                        || user.getSpec().getSecretName().isBlank()
                ? user.getMetadata().getName() + "-s3"
                : user.getSpec().getSecretName();
    }
}
