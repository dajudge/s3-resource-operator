package com.dajudge.s3operator;

import com.dajudge.kindcontainer.KindContainer;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static io.fabric8.kubernetes.client.Config.fromKubeconfig;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class VersityGatewayE2ETest {

    @Container
    static final KindContainer<?> KUBE = new KindContainer<>()
            .withKubectl(kubectl -> kubectl.apply
                    .namespace("default")
                    .fileFromClasspath("versitygw.yaml")
                    .run());

    @Test
    void provisionsUserAndBucketAndServesS3FromInsideKind() throws Exception {
        try (KubernetesClient client = new KubernetesClientBuilder()
                .withConfig(fromKubeconfig(KUBE.getKubeconfig()))
                .build()) {

            try {
                client.apps().deployments()
                        .inNamespace("default")
                        .withName("versitygw")
                        .waitUntilReady(2, TimeUnit.MINUTES);
            } catch (RuntimeException e) {
                dumpVersityDiagnostics(client);
                throw e;
            }

            try (LocalPortForward portForward = client.services()
                    .inNamespace("default")
                    .withName("versitygw")
                    .portForward(7070)) {

                String endpoint = "http://127.0.0.1:" + portForward.getLocalPort();
                String userAccess = "e2e-user";
                String userSecret = "e2e-user-secret";
                String bucket = "e2e-bucket";

                VersityS3Provider provider = new VersityS3Provider();
                provider.createUser(endpoint, "test-root-access", "test-root-secret",
                        userAccess, userSecret, "user");
                provider.createBucket(endpoint, "test-root-access", "test-root-secret",
                        bucket, userAccess);

                try (S3Client s3 = S3Client.builder()
                        .endpointOverride(URI.create(endpoint))
                        .region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(userAccess, userSecret)))
                        .serviceConfiguration(S3Configuration.builder()
                                .pathStyleAccessEnabled(true)
                                .build())
                        .httpClientBuilder(UrlConnectionHttpClient.builder())
                        .build()) {

                    String key = "hello.txt";
                    String payload = "hello from kindcontainer";

                    s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                            RequestBody.fromString(payload, StandardCharsets.UTF_8));

                    String actual = s3.getObjectAsBytes(GetObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(key)
                                    .build())
                            .asUtf8String();

                    assertThat(actual).isEqualTo(payload);
                }
            }
        }
    }

    private static void dumpVersityDiagnostics(KubernetesClient client) {
        System.err.println("=== VersityGW deployment ===");
        System.err.println(client.apps().deployments()
                .inNamespace("default")
                .withName("versitygw")
                .get());

        System.err.println("=== VersityGW pods ===");
        var pods = client.pods()
                .inNamespace("default")
                .withLabel("app", "versitygw")
                .list()
                .getItems();
        for (var pod : pods) {
            var podName = pod.getMetadata().getName();
            System.err.println(pod);
            System.err.println("=== logs: " + podName + " ===");
            try {
                System.err.println(client.pods()
                        .inNamespace("default")
                        .withName(podName)
                        .getLog());
            } catch (RuntimeException logError) {
                System.err.println("Unable to fetch logs: " + logError);
            }
        }
    }
}
