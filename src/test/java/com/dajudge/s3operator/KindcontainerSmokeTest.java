package com.dajudge.s3operator;

import com.dajudge.kindcontainer.KindContainer;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static io.fabric8.kubernetes.client.Config.fromKubeconfig;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class KindcontainerSmokeTest {

    @Container
    static final KindContainer<?> KUBE = new KindContainer<>();

    @Test
    void kubernetesApiIsReachable() {
        try (KubernetesClient client = new KubernetesClientBuilder()
                .withConfig(fromKubeconfig(KUBE.getKubeconfig()))
                .build()) {
            assertThat(client.nodes().list().getItems()).hasSize(1);
        }
    }
}
