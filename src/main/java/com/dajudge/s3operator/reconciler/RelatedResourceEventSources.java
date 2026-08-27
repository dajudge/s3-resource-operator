package com.dajudge.s3operator.reconciler;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSourceContext;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSourceConfiguration;

import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

final class RelatedResourceEventSources {
    private RelatedResourceEventSources() {
    }

    static <S extends HasMetadata, P extends HasMetadata> InformerEventSource<S, P> informer(
            Class<S> secondaryType,
            Class<P> primaryType,
            EventSourceContext<P> context,
            Function<S, Set<ResourceID>> mapper) {
        var configuration = InformerEventSourceConfiguration.from(secondaryType, primaryType)
                .withNamespacesInheritedFromController(context)
                .withSecondaryToPrimaryMapper(mapper::apply)
                .build();
        return new InformerEventSource<>(configuration, context);
    }

    static <P extends HasMetadata> Set<ResourceID> matching(EventSourceContext<P> context, Predicate<P> predicate) {
        return context.getPrimaryCache().list()
                .filter(predicate)
                .map(ResourceID::fromResource)
                .collect(Collectors.toSet());
    }
}
