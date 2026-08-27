package com.dajudge.s3operator;

import com.dajudge.kindcontainer.KindContainer;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.fabric8.kubernetes.client.Config.fromKubeconfig;

public class KindVersityTestResource implements QuarkusTestResourceLifecycleManager {
    private KindContainer<?> kube;
    private KubernetesClient client;
    private LocalPortForward portForward;
    private Path kubeconfig;

    @Override
    public Map<String, String> start() {
        try {
            kube = new KindContainer<>()
                    .withKubectl(kubectl -> kubectl.apply
                            .namespace("default")
                            .fileFromClasspath("versitygw.yaml")
                            .run());
            kube.start();

            kubeconfig = Files.createTempFile("s3-resource-operator-", ".kubeconfig");
            Files.writeString(kubeconfig, kube.getKubeconfig());

            client = new KubernetesClientBuilder()
                    .withConfig(fromKubeconfig(kube.getKubeconfig()))
                    .build();

            installCrds();

            client.apps().deployments()
                    .inNamespace("default")
                    .withName("versitygw")
                    .waitUntilReady(120, java.util.concurrent.TimeUnit.SECONDS);

            portForward = client.services()
                    .inNamespace("default")
                    .withName("versitygw")
                    .portForward(7070);

            return Map.of(
                    "quarkus.kubernetes-client.kubeconfig-file", kubeconfig.toAbsolutePath().toString(),
                    "test.s3.endpoint", "http://127.0.0.1:" + portForward.getLocalPort()
            );
        } catch (Exception e) {
            stop();
            throw new RuntimeException("Unable to start Kindcontainer/Versity test environment", e);
        }
    }

    private void installCrds() throws Exception {
        Path crds = Path.of("charts/s3-resource-operator/crds/s3.dajudge.com.yaml");
        try (InputStream input = Files.newInputStream(crds)) {
            client.load(input).createOrReplace();
        }
    }

    @Override
    public void stop() {
        if (portForward != null) {
            try {
                portForward.close();
            } catch (Exception ignored) {
                // Best-effort cleanup.
            }
            portForward = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
        if (kube != null) {
            kube.stop();
            kube = null;
        }
        if (kubeconfig != null) {
            try {
                Files.deleteIfExists(kubeconfig);
            } catch (Exception ignored) {
                // Best-effort cleanup of a temporary test file.
            }
            kubeconfig = null;
        }
    }
}
