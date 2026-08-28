from pathlib import Path

source = Path("src/test/java/com/dajudge/s3operator/ExistingBehaviorE2ETest.java")
text = source.read_text()

start = text.index("    @Test\n    void generatedCrdsExposeExpectedKindsPluralsAndEnums()")
end = text.index("    private void createBucket(", start)
text = text[:start] + text[end:]

for unused_import in [
    "import static org.assertj.core.api.Assertions.assertThatThrownBy;\n",
    "import java.io.ByteArrayInputStream;\n",
    "import java.nio.file.Files;\n",
    "import java.nio.file.Path;\n",
]:
    text = text.replace(unused_import, "")
source.write_text(text)

contract = '''package com.dajudge.s3operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(K3sVersityTestResource.class)
class ApiContractE2ETest extends OperatorE2ETestSupport {

    @Test
    void generatedCrdsExposeExpectedKindsPluralsAndEnums() throws Exception {
        String buckets = Files.readString(Path.of("target/kubernetes/s3buckets.s3.dajudge.com-v1.yml"));
        String users = Files.readString(Path.of("target/kubernetes/s3users.s3.dajudge.com-v1.yml"));
        String backends = Files.readString(Path.of("target/kubernetes/s3backends.s3.dajudge.com-v1.yml"));
        assertThat(buckets).contains("kind: \\\"S3Bucket\\\"", "plural: \\\"s3buckets\\\"", "\\\"RETAIN\\\"", "\\\"DELETE\\\"");
        assertThat(users).contains("kind: \\\"S3User\\\"", "plural: \\\"s3users\\\"");
        assertThat(backends).contains("kind: \\\"S3Backend\\\"", "plural: \\\"s3backends\\\"");
    }

    @Test
    void rawApiRoundTripAppliesDefaultsAndRejectsInvalidEnum() {
        createRaw("""
                apiVersion: s3.dajudge.com/v1alpha1
                kind: S3Backend
                metadata:
                  name: raw-default-backend
                spec:
                  endpoint: http://127.0.0.1:1
                  adminCredentialsSecretRef:
                    name: raw-missing-admin
                """);
        S3Backend backend = client.resources(S3Backend.class)
                .inNamespace(NS)
                .withName("raw-default-backend")
                .get();
        assertThat(backend.getSpec().getProvider()).isEqualTo("versity");
        createRaw("""
                apiVersion: s3.dajudge.com/v1alpha1
                kind: S3User
                metadata:
                  name: raw-default-user
                spec:
                  backendRef: raw-default-backend
                """);
        S3User user = client.resources(S3User.class)
                .inNamespace(NS)
                .withName("raw-default-user")
                .get();
        assertThat(user.getSpec().getRole()).isEqualTo("user");
        assertThat(user.getSpec().getSecretName()).isNull();
        createRaw("""
                apiVersion: s3.dajudge.com/v1alpha1
                kind: S3Bucket
                metadata:
                  name: raw-default-bucket
                spec:
                  backendRef: raw-default-backend
                  userRef: raw-default-user
                """);
        S3Bucket bucket = client.resources(S3Bucket.class)
                .inNamespace(NS)
                .withName("raw-default-bucket")
                .get();
        assertThat(bucket.getSpec().getDeletionPolicy()).isEqualTo(S3BucketSpec.DeletionPolicy.RETAIN);
        assertThat(bucket.getSpec().getBucketName()).isNull();
        assertThatThrownBy(() -> createRaw("""
                        apiVersion: s3.dajudge.com/v1alpha1
                        kind: S3Bucket
                        metadata:
                          name: invalid-policy-bucket
                        spec:
                          backendRef: raw-default-backend
                          userRef: raw-default-user
                          deletionPolicy: DESTROY_EVERYTHING
                        """)).isInstanceOf(KubernetesClientException.class);
    }

    private void createRaw(String yaml) {
        client.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
                .inNamespace(NS)
                .create();
    }
}
'''
Path("src/test/java/com/dajudge/s3operator/ApiContractE2ETest.java").write_text(contract)
