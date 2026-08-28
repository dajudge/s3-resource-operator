package com.dajudge.s3operator.reconciler;

import com.dajudge.s3operator.api.S3Backend;
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
        var backends = informer(
                S3Backend.class,
                S3User.class,
                context,
                backend -> matching(
                        context,
                        user -> ResourceValidation.hasUsableUserSpec(user)
                                && sameNamespace(user, backend)
                                && backend.getMetadata()
                                        .getName()
                                        .equals(user.getSpec().getBackendRef())));
        var secrets = informer(
                Secret.class,
                S3User.class,
                context,
                secret -> matching(
                        context,
                        user -> ResourceValidation.hasUsableUserSpec(user) && secretAffectsUser(client, secret, user)));
        return List.of(backends, secrets);
    }

    private static boolean secretAffectsUser(KubernetesClient client, Secret secret, S3User user) {
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

    private static boolean sameNamespace(HasMetadata primary, HasMetadata secondary) {
        return primary.getMetadata()
                .getNamespace()
                .equals(secondary.getMetadata().getNamespace());
    }

    private static String userSecretName(S3User user) {
        return user.getSpec().getSecretName() == null
                        || user.getSpec().getSecretName().isBlank()
                ? user.getMetadata().getName() + "-s3"
                : user.getSpec().getSecretName();
    }
}
