package com.dajudge.s3operator.reconciler;

import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3Instance;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@ApplicationScoped
@ControllerConfiguration
public class S3BucketReconciler implements Reconciler<S3Bucket> {

    @Inject
    KubernetesClient client;

    @Inject
    VersityS3Provider provider;

    @Override
    public UpdateControl<S3Bucket> reconcile(S3Bucket bucket, Context<S3Bucket> context) {
        String namespace = bucket.getMetadata().getNamespace();
        S3Instance instance = client.resources(S3Instance.class)
                .inNamespace(namespace)
                .withName(bucket.getSpec().getInstanceRef())
                .get();
        if (instance == null) {
            throw new IllegalStateException("S3Instance not found: " + bucket.getSpec().getInstanceRef());
        }
        if (!provider.type().equals(instance.getSpec().getProvider())) {
            throw new IllegalStateException("Unsupported S3 provider: " + instance.getSpec().getProvider());
        }

        S3User owner = client.resources(S3User.class)
                .inNamespace(namespace)
                .withName(bucket.getSpec().getOwnerRef())
                .get();
        if (owner == null) {
            throw new IllegalStateException("S3User not found: " + bucket.getSpec().getOwnerRef());
        }

        String ownerSecretName = owner.getSpec().getSecretName() == null || owner.getSpec().getSecretName().isBlank()
                ? owner.getMetadata().getName() + "-s3"
                : owner.getSpec().getSecretName();
        Secret ownerSecret = client.secrets().inNamespace(namespace).withName(ownerSecretName).get();
        if (ownerSecret == null) {
            throw new IllegalStateException("Owner credentials Secret not found: " + ownerSecretName);
        }

        Secret adminSecret = client.secrets().inNamespace(namespace)
                .withName(instance.getSpec().getAdminCredentialsSecretRef().getName())
                .get();
        if (adminSecret == null) {
            throw new IllegalStateException("Admin credentials Secret not found");
        }

        String bucketName = bucket.getSpec().getBucketName() == null || bucket.getSpec().getBucketName().isBlank()
                ? bucket.getMetadata().getName()
                : bucket.getSpec().getBucketName();
        provider.createBucket(instance.getSpec().getEndpoint(),
                secretValue(adminSecret, "accessKey"), secretValue(adminSecret, "secretKey"),
                bucketName, secretValue(ownerSecret, "accessKey"));

        return UpdateControl.noUpdate();
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
