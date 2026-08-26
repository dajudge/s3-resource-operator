package com.dajudge.s3operator.reconciler;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3BucketStatus;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@ApplicationScoped
@ControllerConfiguration
public class S3BucketReconciler implements Reconciler<S3Bucket>, Cleaner<S3Bucket> {

    @Inject
    KubernetesClient client;

    @Inject
    VersityS3Provider provider;

    @Override
    public UpdateControl<S3Bucket> reconcile(S3Bucket bucket, Context<S3Bucket> context) {
        String namespace = bucket.getMetadata().getNamespace();
        S3Backend backend = requireBackend(namespace, bucket.getSpec().getBackendRef());
        S3User user = requireUser(namespace, bucket.getSpec().getUserRef());
        Secret userSecret = requireUserSecret(namespace, user);
        Secret adminSecret = requireAdminSecret(namespace, backend);

        String bucketName = bucketName(bucket);
        provider.createBucket(backend.getSpec().getEndpoint(),
                secretValue(adminSecret, "accessKey"), secretValue(adminSecret, "secretKey"),
                bucketName, secretValue(userSecret, "accessKey"));

        S3BucketStatus status = bucket.getStatus() == null ? new S3BucketStatus() : bucket.getStatus();
        status.setObservedGeneration(bucket.getMetadata().getGeneration());
        status.setConditions(List.of(new ConditionBuilder()
                .withType("Ready")
                .withStatus("True")
                .withReason("Reconciled")
                .withMessage("Versity bucket is ready")
                .withObservedGeneration(bucket.getMetadata().getGeneration())
                .withLastTransitionTime(Instant.now().toString())
                .build()));
        bucket.setStatus(status);
        return UpdateControl.patchStatus(bucket);
    }

    @Override
    public DeleteControl cleanup(S3Bucket bucket, Context<S3Bucket> context) {
        if (bucket.getSpec().getDeletionPolicy() == S3BucketSpec.DeletionPolicy.DELETE) {
            String namespace = bucket.getMetadata().getNamespace();
            S3Backend backend = requireBackend(namespace, bucket.getSpec().getBackendRef());
            Secret adminSecret = requireAdminSecret(namespace, backend);
            provider.deleteBucket(backend.getSpec().getEndpoint(),
                    secretValue(adminSecret, "accessKey"), secretValue(adminSecret, "secretKey"),
                    bucketName(bucket));
        }
        return DeleteControl.defaultDelete();
    }

    private S3Backend requireBackend(String namespace, String name) {
        S3Backend backend = client.resources(S3Backend.class)
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (backend == null) {
            throw new IllegalStateException("S3Backend not found: " + name);
        }
        if (!provider.type().equals(backend.getSpec().getProvider())) {
            throw new IllegalStateException("Unsupported S3 provider: " + backend.getSpec().getProvider());
        }
        return backend;
    }

    private S3User requireUser(String namespace, String name) {
        S3User user = client.resources(S3User.class)
                .inNamespace(namespace)
                .withName(name)
                .get();
        if (user == null) {
            throw new IllegalStateException("S3User not found: " + name);
        }
        return user;
    }

    private Secret requireUserSecret(String namespace, S3User user) {
        String secretName = user.getSpec().getSecretName() == null || user.getSpec().getSecretName().isBlank()
                ? user.getMetadata().getName() + "-s3"
                : user.getSpec().getSecretName();
        Secret secret = client.secrets().inNamespace(namespace).withName(secretName).get();
        if (secret == null) {
            throw new IllegalStateException("User credentials Secret not found: " + secretName);
        }
        return secret;
    }

    private Secret requireAdminSecret(String namespace, S3Backend backend) {
        Secret secret = client.secrets().inNamespace(namespace)
                .withName(backend.getSpec().getAdminCredentialsSecretRef().getName())
                .get();
        if (secret == null) {
            throw new IllegalStateException("Admin credentials Secret not found");
        }
        return secret;
    }

    private static String bucketName(S3Bucket bucket) {
        return bucket.getSpec().getBucketName() == null || bucket.getSpec().getBucketName().isBlank()
                ? bucket.getMetadata().getName()
                : bucket.getSpec().getBucketName();
    }

    private static String secretValue(Secret secret, String key) {
        if (secret.getData() != null && secret.getData().containsKey(key)) {
            return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
        }
        if (secret.getStringData() != null && secret.getStringData().containsKey(key)) {
            return secret.getStringData().get(key);
        }
        throw new IllegalStateException("Missing key '" + key + "' in Secret " + secret.getMetadata().getName());
    }
}
