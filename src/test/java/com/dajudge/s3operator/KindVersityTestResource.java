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
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class KindVersityTestResource implements QuarkusTestResourceLifecycleManager {
    private static final String KUBECONFIG_PROPERTY = "quarkus.kubernetes-client.kubeconfig-file";
    private static final String ENDPOINT_PROPERTY = "test.s3.endpoint";
    private static final String JVM_ENVIRONMENT_KEY =
            KindVersityTestResource.class.getName() + ".jvm-environment";

    @Override
    public Map<String, String> start() {
        Properties systemProperties = System.getProperties();
        synchronized (systemProperties) {
            Object existing = systemProperties.get(JVM_ENVIRONMENT_KEY);
            if (existing instanceof Map<?, ?> configuration) {
                return restoreConfiguration(configuration);
            }

            SharedEnvironment environment = SharedEnvironment.start();
            Map<String, String> configuration = Map.of(
                    KUBECONFIG_PROPERTY, environment.kubeconfigPath(),
                    ENDPOINT_PROPERTY, environment.endpoint());
            systemProperties.put(JVM_ENVIRONMENT_KEY, configuration);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                environment.close();
                synchronized (systemProperties) {
                    systemProperties.remove(JVM_ENVIRONMENT_KEY);
                }
                System.clearProperty(KUBECONFIG_PROPERTY);
                System.clearProperty(ENDPOINT_PROPERTY);
            }));
            return restoreConfiguration(configuration);
        }
    }

    private static Map<String, String> restoreConfiguration(Map<?, ?> configuration) {
        String kubeconfigPath = (String) configuration.get(KUBECONFIG_PROPERTY);
        String endpoint = (String) configuration.get(ENDPOINT_PROPERTY);
        System.setProperty(KUBECONFIG_PROPERTY, kubeconfigPath);
        System.setProperty(ENDPOINT_PROPERTY, endpoint);
        return Map.of(
                KUBECONFIG_PROPERTY, kubeconfigPath,
                ENDPOINT_PROPERTY, endpoint);
    }

    @Override
    public void stop() {
        // Quarkus restarts test resources in separate classloaders when switching test
        // profiles. The JVM-global System properties registry keeps the K3s/Versity
        // environment alive until the shutdown hook cleans it up.
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
                client.apps()
                        .deployments()
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
