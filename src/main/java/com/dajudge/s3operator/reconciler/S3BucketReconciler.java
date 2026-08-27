package com.dajudge.s3operator.reconciler;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3BucketStatus;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.provider.S3ProviderException;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.EventSourceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.ADMIN_CREDENTIALS_NOT_FOUND;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.BACKEND_NOT_FOUND;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.INVALID_CREDENTIALS_SECRET;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.PROVIDER_ERROR;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.UNSUPPORTED_PROVIDER;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.USER_CREDENTIALS_NOT_FOUND;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.USER_NOT_FOUND;

@ApplicationScoped
@ControllerConfiguration
public class S3BucketReconciler implements Reconciler<S3Bucket>, Cleaner<S3Bucket> {
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    @Inject KubernetesClient client;
    @Inject VersityS3Provider provider;
    @ConfigProperty(name = "s3.operator.resync-interval", defaultValue = "1m") Duration resyncInterval;

    @Override
    public UpdateControl<S3Bucket> reconcile(S3Bucket bucket, Context<S3Bucket> context) {
        try {
            String namespace = bucket.getMetadata().getNamespace();
            S3Backend backend = requireBackend(namespace, bucket.getSpec().getBackendRef());
            S3User user = requireUser(namespace, bucket.getSpec().getUserRef());
            Secret userSecret = requireUserSecret(namespace, user);
            Secret adminSecret = requireAdminSecret(namespace, backend);

            try {
                provider.createBucket(backend.getSpec().getEndpoint(),
                        secretValue(adminSecret, "accessKey"), secretValue(adminSecret, "secretKey"),
                        bucketName(bucket), secretValue(userSecret, "accessKey"));
            } catch (S3ProviderException e) {
                throw new ReconciliationException(PROVIDER_ERROR, e.getMessage(), e);
            }

            S3BucketStatus status = status(bucket);
            setCondition(bucket, status, "True", "Reconciled", "Versity bucket is ready");
            return UpdateControl.patchStatus(bucket).rescheduleAfter(resyncInterval);
        } catch (ReconciliationException e) {
            S3BucketStatus status = status(bucket);
            setCondition(bucket, status, "False", e.reason().conditionReason(), e.getMessage());
            return UpdateControl.patchStatus(bucket).rescheduleAfter(RETRY_DELAY);
        }
    }

    @Override
    public List<EventSource<?, S3Bucket>> prepareEventSources(EventSourceContext<S3Bucket> context) {
        var backends = RelatedResourceEventSources.informer(S3Backend.class, S3Bucket.class, context,
                backend -> RelatedResourceEventSources.matching(context, bucket -> sameNamespace(bucket, backend)
                        && backend.getMetadata().getName().equals(bucket.getSpec().getBackendRef())));
        var users = RelatedResourceEventSources.informer(S3User.class, S3Bucket.class, context,
                user -> RelatedResourceEventSources.matching(context, bucket -> sameNamespace(bucket, user)
                        && user.getMetadata().getName().equals(bucket.getSpec().getUserRef())));
        var secrets = RelatedResourceEventSources.informer(Secret.class, S3Bucket.class, context,
                secret -> RelatedResourceEventSources.matching(context, bucket -> secretAffectsBucket(secret, bucket)));
        return List.of(backends, users, secrets);
    }

    @Override
    public DeleteControl cleanup(S3Bucket bucket, Context<S3Bucket> context) {
        if (bucket.getSpec().getDeletionPolicy() != S3BucketSpec.DeletionPolicy.DELETE) return DeleteControl.defaultDelete();

        String namespace = bucket.getMetadata().getNamespace();
        S3Backend backend = client.resources(S3Backend.class).inNamespace(namespace)
                .withName(bucket.getSpec().getBackendRef()).get();
        if (backend == null || !provider.type().equals(backend.getSpec().getProvider())) return DeleteControl.defaultDelete();

        Secret adminSecret = client.secrets().inNamespace(namespace)
                .withName(backend.getSpec().getAdminCredentialsSecretRef().getName()).get();
        if (adminSecret == null) return DeleteControl.defaultDelete();

        provider.deleteBucket(backend.getSpec().getEndpoint(), secretValue(adminSecret, "accessKey"),
                secretValue(adminSecret, "secretKey"), bucketName(bucket));
        return DeleteControl.defaultDelete();
    }

    private boolean secretAffectsBucket(Secret secret, S3Bucket bucket) {
        if (!sameNamespace(bucket, secret)) return false;
        String namespace = bucket.getMetadata().getNamespace();
        S3User user = client.resources(S3User.class).inNamespace(namespace).withName(bucket.getSpec().getUserRef()).get();
        if (user != null && secret.getMetadata().getName().equals(userSecretName(user))) return true;
        S3Backend backend = client.resources(S3Backend.class).inNamespace(namespace)
                .withName(bucket.getSpec().getBackendRef()).get();
        return backend != null && backend.getSpec().getAdminCredentialsSecretRef() != null
                && secret.getMetadata().getName().equals(backend.getSpec().getAdminCredentialsSecretRef().getName());
    }

    private static boolean sameNamespace(S3Bucket bucket, HasMetadata secondary) {
        return bucket.getMetadata().getNamespace().equals(secondary.getMetadata().getNamespace());
    }

    private S3Backend requireBackend(String namespace, String name) {
        S3Backend backend = client.resources(S3Backend.class).inNamespace(namespace).withName(name).get();
        if (backend == null) throw new ReconciliationException(BACKEND_NOT_FOUND, "S3Backend not found: " + name);
        if (!provider.type().equals(backend.getSpec().getProvider()))
            throw new ReconciliationException(UNSUPPORTED_PROVIDER,
                    "Unsupported S3 provider: " + backend.getSpec().getProvider());
        return backend;
    }

    private S3User requireUser(String namespace, String name) {
        S3User user = client.resources(S3User.class).inNamespace(namespace).withName(name).get();
        if (user == null) throw new ReconciliationException(USER_NOT_FOUND, "S3User not found: " + name);
        return user;
    }

    private Secret requireUserSecret(String namespace, S3User user) {
        String name = userSecretName(user);
        Secret secret = client.secrets().inNamespace(namespace).withName(name).get();
        if (secret == null) throw new ReconciliationException(USER_CREDENTIALS_NOT_FOUND,
                "User credentials Secret not found: " + name);
        return secret;
    }

    private Secret requireAdminSecret(String namespace, S3Backend backend) {
        Secret secret = client.secrets().inNamespace(namespace)
                .withName(backend.getSpec().getAdminCredentialsSecretRef().getName()).get();
        if (secret == null) throw new ReconciliationException(ADMIN_CREDENTIALS_NOT_FOUND,
                "Admin credentials Secret not found");
        return secret;
    }

    private static S3BucketStatus status(S3Bucket bucket) {
        return bucket.getStatus() == null ? new S3BucketStatus() : bucket.getStatus();
    }

    private static void setCondition(S3Bucket bucket, S3BucketStatus status, String value, String reason, String message) {
        status.setObservedGeneration(bucket.getMetadata().getGeneration());
        status.setConditions(List.of(new ConditionBuilder().withType("Ready").withStatus(value).withReason(reason)
                .withMessage(message == null ? reason : message).withObservedGeneration(bucket.getMetadata().getGeneration())
                .withLastTransitionTime(transitionTime(status.getConditions(), value, reason)).build()));
        bucket.setStatus(status);
    }

    private static String userSecretName(S3User user) {
        return user.getSpec().getSecretName() == null || user.getSpec().getSecretName().isBlank()
                ? user.getMetadata().getName() + "-s3" : user.getSpec().getSecretName();
    }

    private static String bucketName(S3Bucket bucket) {
        return bucket.getSpec().getBucketName() == null || bucket.getSpec().getBucketName().isBlank()
                ? bucket.getMetadata().getName() : bucket.getSpec().getBucketName();
    }

    private static String transitionTime(List<Condition> conditions, String status, String reason) {
        if (conditions != null) for (Condition c : conditions)
            if ("Ready".equals(c.getType()) && status.equals(c.getStatus()) && reason.equals(c.getReason())
                    && c.getLastTransitionTime() != null) return c.getLastTransitionTime();
        return Instant.now().toString();
    }

    private static String secretValue(Secret secret, String key) {
        if (secret.getData() != null && secret.getData().containsKey(key))
            return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
        if (secret.getStringData() != null && secret.getStringData().containsKey(key)) return secret.getStringData().get(key);
        throw new ReconciliationException(INVALID_CREDENTIALS_SECRET,
                "Missing key '" + key + "' in Secret " + secret.getMetadata().getName());
    }
}
