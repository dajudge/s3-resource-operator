package com.dajudge.s3operator;

import static io.fabric8.kubernetes.client.Config.fromKubeconfig;

import com.dajudge.kindcontainer.K3sContainer;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class KindVersityTestResource implements QuarkusTestResourceLifecycleManager {
    private static final String KUBECONFIG_PROPERTY = "quarkus.kubernetes-client.kubeconfig-file";
    private static final String ENDPOINT_PROPERTY = "test.s3.endpoint";
    private static final Object LOCK = new Object();

    private static SharedEnvironment sharedEnvironment;

    @Override
    public Map<String, String> start() {
        SharedEnvironment environment;
        synchronized (LOCK) {
            if (sharedEnvironment == null) {
                sharedEnvironment = SharedEnvironment.start();
                Runtime.getRuntime().addShutdownHook(new Thread(KindVersityTestResource::shutdown));
            }
            environment = sharedEnvironment;
        }

        System.setProperty(KUBECONFIG_PROPERTY, environment.kubeconfigPath());
        System.setProperty(ENDPOINT_PROPERTY, environment.endpoint());
        return Map.of(
                KUBECONFIG_PROPERTY, environment.kubeconfigPath(),
                ENDPOINT_PROPERTY, environment.endpoint());
    }

    @Override
    public void stop() {
        // Quarkus restarts test resources when switching test profiles. Keep the expensive
        // K3s/Versity environment alive for the whole test JVM and clean it up from the
        // shutdown hook instead.
    }

    private static void shutdown() {
        synchronized (LOCK) {
            if (sharedEnvironment != null) {
                sharedEnvironment.close();
                sharedEnvironment = null;
            }
        }
        System.clearProperty(KUBECONFIG_PROPERTY);
        System.clearProperty(ENDPOINT_PROPERTY);
    }

    private record SharedEnvironment(
            K3sContainer<?> kube,
            KubernetesClient client,
            LocalPortForward portForward,
            Path kubeconfig,
            String kubeconfigPath,
            String endpoint) {

        private static SharedEnvironment start() {
            K3sContainer<?> kube = null;
            KubernetesClient client = null;
            LocalPortForward portForward = null;
            Path kubeconfig = null;
            try {
                kube = new K3sContainer<>()
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

                installCrds(client);
                client.apps().deployments()
                        .inNamespace("default")
                        .withName("versitygw")
                        .waitUntilReady(120, TimeUnit.SECONDS);

                portForward = client.services()
                        .inNamespace("default")
                        .withName("versitygw")
                        .portForward(7070);

                String kubeconfigPath = kubeconfig.toAbsolutePath().toString();
                String endpoint = "http://127.0.0.1:" + portForward.getLocalPort();
                return new SharedEnvironment(kube, client, portForward, kubeconfig, kubeconfigPath, endpoint);
            } catch (Exception e) {
                close(portForward, client, kube, kubeconfig);
                throw new RuntimeException("Unable to start K3s/Versity test environment", e);
            }
        }

        private static void installCrds(KubernetesClient client) throws Exception {
            Path crds = Path.of("charts/s3-resource-operator/crds/s3.dajudge.com.yaml");
            try (InputStream input = Files.newInputStream(crds)) {
                client.load(input).createOrReplace();
            }
        }

        private void close() {
            close(portForward, client, kube, kubeconfig);
        }

        private static void close(
                LocalPortForward portForward, KubernetesClient client, K3sContainer<?> kube, Path kubeconfig) {
            if (portForward != null) {
                try {
                    portForward.close();
                } catch (Exception ignored) {
                    // Best-effort cleanup.
                }
            }
            if (client != null) {
                client.close();
            }
            if (kube != null) {
                kube.stop();
            }
            if (kubeconfig != null) {
                try {
                    Files.deleteIfExists(kubeconfig);
                } catch (Exception ignored) {
                    // Best-effort cleanup of a temporary test file.
                }
            }
        }
    }
}
