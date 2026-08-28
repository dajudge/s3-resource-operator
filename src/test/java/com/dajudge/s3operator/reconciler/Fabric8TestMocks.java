package com.dajudge.s3operator.reconciler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import java.util.List;

final class Fabric8TestMocks {
    private static final String NS = "ns";

    private Fabric8TestMocks() {}

    @SuppressWarnings("unchecked")
    static <T extends HasMetadata> void stubResourceGet(KubernetesClient client, Class<T> type, String name, T value) {
        MixedOperation<T, KubernetesResourceList<T>, Resource<T>> operation = mock(MixedOperation.class);
        NonNamespaceOperation<T, KubernetesResourceList<T>, Resource<T>> namespaced = mock(NonNamespaceOperation.class);
        Resource<T> resource = mock(Resource.class);
        when(client.resources(type)).thenReturn(operation);
        when(operation.inNamespace(NS)).thenReturn(namespaced);
        when(namespaced.withName(name)).thenReturn(resource);
        when(resource.get()).thenReturn(value);
    }

    @SuppressWarnings("unchecked")
    static <T extends HasMetadata> void stubResourceList(KubernetesClient client, Class<T> type, List<T> items) {
        MixedOperation<T, KubernetesResourceList<T>, Resource<T>> operation = mock(MixedOperation.class);
        NonNamespaceOperation<T, KubernetesResourceList<T>, Resource<T>> namespaced = mock(NonNamespaceOperation.class);
        KubernetesResourceList<T> list = mock(KubernetesResourceList.class);
        when(client.resources(type)).thenReturn(operation);
        when(operation.inNamespace(NS)).thenReturn(namespaced);
        when(namespaced.list()).thenReturn(list);
        when(list.getItems()).thenReturn(items);
    }

    @SuppressWarnings("unchecked")
    static MixedOperation<Secret, SecretList, Resource<Secret>> stubSecrets(
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
}

record SecretResult(String name, Secret secret) {}
