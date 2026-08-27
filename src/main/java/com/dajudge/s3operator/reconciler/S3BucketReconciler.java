package com.dajudge.s3operator.reconciler;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3BucketStatus;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.provider.S3ProviderException;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.List;

import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.PROVIDER_ERROR;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.USER_CREDENTIALS_NOT_FOUND;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.USER_NOT_FOUND;
import static com.dajudge.s3operator.reconciler.ReconcilerSupport.requireAdminSecret;
import static com.dajudge.s3operator.reconciler.ReconcilerSupport.requireBackend;
import static com.dajudge.s3operator.reconciler.ReconcilerSupport.secretValue;
import static com.dajudge.s3operator.reconciler.ReconcilerSupport.transitionTime;

@ApplicationScoped
@ControllerConfiguration
public class S3BucketReconciler implements Reconciler<S3Bucket>, Cleaner<S3Bucket> {
    @Inject KubernetesClient client;
    @Inject VersityS3Provider provider;
    @ConfigProperty(name = "s3.operator.resync-interval", defaultValue = "1m") Duration resyncInterval;
    @ConfigProperty(name = "s3.operator.retry-delay", defaultValue = "5s") Duration retryDelay;

    @Override
    public UpdateControl<S3Bucket> reconcile(S3Bucket bucket, Context<S3Bucket> context) {
        try {
            ResourceValidation.validateBucket(bucket);
            String namespace = bucket.getMetadata().getNamespace();
            S3Backend backend = requireBackend(client, provider, namespace, bucket.getSpec().getBackendRef());
            S3User user = requireUser(namespace, bucket.getSpec().getUserRef());
            Secret userSecret = requireUserSecret(namespace, user);
            Secret adminSecret = requireAdminSecret(client, namespace, backend);

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
            return UpdateControl.patchStatus(bucket).rescheduleAfter(retryDelay);
        }
    }

    @Override
    public List<EventSource<?, S3Bucket>> prepareEventSources(EventSourceContext<S3Bucket> context) {
        var backends = RelatedResourceEventSources.informer(S3Backend.class, S3Bucket.class, context,
                backend -> RelatedResourceEventSources.matching(context, bucket -> ResourceValidation.hasUsableBucketSpec(bucket)
                        && sameNamespace(bucket, backend)
                        && backend.getMetadata().getName().equals(bucket.getSpec().getBackendRef())));
        var users = RelatedResourceEventSources.informer(S3User.class, S3Bucket.class, context,
                user -> RelatedResourceEventSources.matching(context, bucket -> ResourceValidation.hasUsableBucketSpec(bucket)
                        && sameNamespace(bucket, user)
                        && user.getMetadata().getName().equals(bucket.getSpec().getUserRef())));
        var secrets = RelatedResourceEventSources.informer(Secret.class, S3Bucket.class, context,
                secret -> RelatedResourceEventSources.matching(context,
                        bucket -> ResourceValidation.hasUsableBucketSpec(bucket) && secretAffectsBucket(secret, bucket)));
        return List.of(backends, users, secrets);
    }

    @Override
    public DeleteControl cleanup(S3Bucket bucket, Context<S3Bucket> context) {
        if (!ResourceValidation.hasUsableBucketSpec(bucket)
                || bucket.getSpec().getDeletionPolicy() != S3BucketSpec.DeletionPolicy.DELETE) {
            return DeleteControl.defaultDelete();
        }

        String namespace = bucket.getMetadata().getNamespace();
        S3Backend backend = client.resources(S3Backend.class).inNamespace(namespace)
                .withName(bucket.getSpec().getBackendRef()).get();
        if (!ResourceValidation.hasUsableBackendSpec(backend)
                || !provider.type().equals(backend.getSpec().getProvider())) return DeleteControl.defaultDelete();

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
        if (ResourceValidation.hasUsableUserSpec(user)
                && secret.getMetadata().getName().equals(userSecretName(user))) return true;
        S3Backend backend = client.resources(S3Backend.class).inNamespace(namespace)
                .withName(bucket.getSpec().getBackendRef()).get();
        return ResourceValidation.hasUsableBackendSpec(backend)
                && secret.getMetadata().getName().equals(backend.getSpec().getAdminCredentialsSecretRef().getName());
    }

    private static boolean sameNamespace(S3Bucket bucket, HasMetadata secondary) {
        return bucket.getMetadata().getNamespace().equals(secondary.getMetadata().getNamespace());
    }

    private S3User requireUser(String namespace, String name) {
        S3User user = client.resources(S3User.class).inNamespace(namespace).withName(name).get();
        if (user == null) throw new ReconciliationException(USER_NOT_FOUND, "S3User not found: " + name);
        ResourceValidation.validateUser(user);
        return user;
    }

    private Secret requireUserSecret(String namespace, S3User user) {
        String name = userSecretName(user);
        Secret secret = client.secrets().inNamespace(namespace).withName(name).get();
        if (secret == null) throw new ReconciliationException(USER_CREDENTIALS_NOT_FOUND,
                "User credentials Secret not found: " + name);
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
}
