from pathlib import Path

existing_path = Path('src/test/java/com/dajudge/s3operator/ExistingBehaviorE2ETest.java')
support_path = Path('src/test/java/com/dajudge/s3operator/OperatorE2ETestSupport.java')
new_path = Path('src/test/java/com/dajudge/s3operator/SecretLifecycleE2ETest.java')

text = existing_path.read_text()
method_start = text.index('    @Test\n    void recreatesGeneratedSecretAndConvergesMutatedCredentialsOnReconcile() {')
method_end = text.index('    @Test\n    void cleanupIsIdempotentWhenExternalResourcesAreAlreadyGone()', method_start)
method = text[method_start:method_end]
text = text[:method_start] + text[method_end:]

bucket_start = text.index('    private void createBucket(\n            String name, String backendRef, String userRef, String bucketName, S3BucketSpec.DeletionPolicy policy) {')
bucket_end = text.index('    private void forceUserReconcile(', bucket_start)
text = text[:bucket_start] + text[bucket_end:]

reconcile_start = text.index('    private void forceUserReconcile(')
reconcile_end = text.index('    private static Condition readyCondition(', reconcile_start)
text = text[:reconcile_start] + text[reconcile_end:]

wait_start = text.index('    private Secret awaitSecretWithDifferentSecret(')
wait_end = text.index('\n}', wait_start)
text = text[:wait_start] + text[wait_end:]

for import_line in (
    'import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;\n',
    'import io.fabric8.kubernetes.client.KubernetesClientException;\n',
    'import java.util.Base64;\n',
):
    text = text.replace(import_line, '')
existing_path.write_text(text)

support = support_path.read_text()
support = support.replace(
    'import io.fabric8.kubernetes.client.KubernetesClient;\n',
    'import io.fabric8.kubernetes.client.KubernetesClient;\nimport io.fabric8.kubernetes.client.KubernetesClientException;\n',
)
insert_at = support.index('    protected S3User awaitUser(')
shared = '''    protected void createBucket(\n            String name, String backendRef, String userRef, String bucketName, S3BucketSpec.DeletionPolicy policy) {\n        S3BucketSpec spec = new S3BucketSpec();\n        spec.setBackendRef(backendRef);\n        spec.setUserRef(userRef);\n        if (bucketName != null) spec.setBucketName(bucketName);\n        if (policy != null) spec.setDeletionPolicy(policy);\n        S3Bucket bucket = new S3Bucket();\n        bucket.setMetadata(\n                new ObjectMetaBuilder().withName(name).withNamespace(NS).build());\n        bucket.setSpec(spec);\n        client.resources(S3Bucket.class).inNamespace(NS).resource(bucket).create();\n    }\n\n    protected void forceUserReconcile(String name, String role) {\n        await().atMost(TIMEOUT)\n                .ignoreException(KubernetesClientException.class)\n                .untilAsserted(() -> client.resources(S3User.class)\n                        .inNamespace(NS)\n                        .withName(name)\n                        .edit(user -> {\n                            user.getSpec().setRole(role);\n                            return user;\n                        }));\n    }\n\n'''
support = support[:insert_at] + shared + support[insert_at:]
support_path.write_text(support)

new_file = '''package com.dajudge.s3operator;\n\nimport static org.assertj.core.api.Assertions.assertThat;\nimport static org.awaitility.Awaitility.await;\n\nimport com.dajudge.s3operator.api.S3BucketSpec;\nimport io.fabric8.kubernetes.api.model.Secret;\nimport io.quarkus.test.common.QuarkusTestResource;\nimport io.quarkus.test.junit.QuarkusTest;\nimport java.nio.charset.StandardCharsets;\nimport java.util.Base64;\nimport org.junit.jupiter.api.Test;\nimport software.amazon.awssdk.services.s3.S3Client;\n\n@QuarkusTest\n@QuarkusTestResource(K3sVersityTestResource.class)\nclass SecretLifecycleE2ETest extends OperatorE2ETestSupport {\n\n'''
new_file += method
new_file += '''    private Secret awaitSecretWithDifferentSecret(String name, String previous) {\n        final Secret[] result = new Secret[1];\n        await().atMost(TIMEOUT).untilAsserted(() -> {\n            Secret secret = client.secrets().inNamespace(NS).withName(name).get();\n            assertThat(secret).isNotNull();\n            assertThat(secretValue(secret, \"secretKey\")).isNotEqualTo(previous);\n            result[0] = secret;\n        });\n        return result[0];\n    }\n}\n'''
new_path.write_text(new_file)
