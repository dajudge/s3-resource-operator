package com.dajudge.s3operator.reconciler;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserStatus;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@ApplicationScoped
@ControllerConfiguration
public class S3UserReconciler implements Reconciler<S3User>, Cleaner<S3User> {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    @Inject KubernetesClient client;
    @Inject VersityS3Provider provider;
    @ConfigProperty(name = "s3.operator.resync-interval", defaultValue = "1m") Duration resyncInterval;

    @Override
    public UpdateControl<S3User> reconcile(S3User user, Context<S3User> context) {
        try {
            String namespace = user.getMetadata().getNamespace();
            S3Backend backend = requireBackend(namespace, user.getSpec().getBackendRef());
            Secret adminSecret = requireAdminSecret(namespace, backend);

            String secretName = secretName(user);
            Secret credentials = client.secrets().inNamespace(namespace).withName(secretName).get();
            if (credentials == null) {
                String accessKey = namespace + "." + user.getMetadata().getName();
                String secretKey = randomSecret();
                credentials = client.secrets().resource(new SecretBuilder()
                        .withNewMetadata().withName(secretName).withNamespace(namespace)
                        .addNewOwnerReference().withApiVersion(user.getApiVersion()).withKind(user.getKind())
                        .withName(user.getMetadata().getName()).withUid(user.getMetadata().getUid())
                        .withController(true).withBlockOwnerDeletion(true).endOwnerReference().endMetadata()
                        .addToStringData("accessKey", accessKey).addToStringData("secretKey", secretKey)
                        .addToStringData("endpoint", backend.getSpec().getEndpoint()).build()).create();
            }

            String accessKey = secretValue(credentials, "accessKey");
            provider.createUser(backend.getSpec().getEndpoint(),
                    secretValue(adminSecret, "accessKey"), secretValue(adminSecret, "secretKey"),
                    accessKey, secretValue(credentials, "secretKey"), user.getSpec().getRole());

            S3UserStatus status = status(user);
            status.setAccessKeyId(accessKey);
            status.setSecretName(secretName);
            setCondition(user, status, "True", "Reconciled", "Versity user and credentials are ready");
            return UpdateControl.patchStatus(user).rescheduleAfter(resyncInterval);
        } catch (RuntimeException e) {
            S3UserStatus status = status(user);
            setCondition(user, status, "False", failureReason(e), e.getMessage());
            return UpdateControl.patchStatus(user).rescheduleAfter(RETRY_DELAY);
        }
    }

    @Override
    public DeleteControl cleanup(S3User user, Context<S3User> context) {
        String namespace = user.getMetadata().getNamespace();
        boolean referenced = client.resources(S3Bucket.class).inNamespace(namespace).list().getItems().stream()
                .anyMatch(bucket -> user.getMetadata().getName().equals(bucket.getSpec().getUserRef()));
        if (referenced) {
            throw new IllegalStateException("S3User is still referenced by an S3Bucket: " + user.getMetadata().getName());
        }

        S3Backend backend = client.resources(S3Backend.class).inNamespace(namespace)
                .withName(user.getSpec().getBackendRef()).get();
        if (backend == null || !provider.type().equals(backend.getSpec().getProvider())) return DeleteControl.defaultDelete();

        Secret adminSecret = client.secrets().inNamespace(namespace)
                .withName(backend.getSpec().getAdminCredentialsSecretRef().getName()).get();
        if (adminSecret == null) return DeleteControl.defaultDelete();

        Secret credentials = client.secrets().inNamespace(namespace).withName(secretName(user)).get();
        String accessKey = credentials != null ? secretValue(credentials, "accessKey")
                : user.getStatus() == null ? null : user.getStatus().getAccessKeyId();
        if (accessKey != null && !accessKey.isBlank()) {
            provider.deleteUser(backend.getSpec().getEndpoint(), secretValue(adminSecret, "accessKey"),
                    secretValue(adminSecret, "secretKey"), accessKey);
        }
        return DeleteControl.defaultDelete();
    }

    private S3Backend requireBackend(String namespace, String name) {
        S3Backend backend = client.resources(S3Backend.class).inNamespace(namespace).withName(name).get();
        if (backend == null) throw new IllegalStateException("S3Backend not found: " + name);
        if (!provider.type().equals(backend.getSpec().getProvider()))
            throw new IllegalStateException("Unsupported S3 provider: " + backend.getSpec().getProvider());
        return backend;
    }

    private Secret requireAdminSecret(String namespace, S3Backend backend) {
        Secret secret = client.secrets().inNamespace(namespace)
                .withName(backend.getSpec().getAdminCredentialsSecretRef().getName()).get();
        if (secret == null) throw new IllegalStateException("Admin credentials Secret not found");
        return secret;
    }

    private static S3UserStatus status(S3User user) {
        return user.getStatus() == null ? new S3UserStatus() : user.getStatus();
    }

    private static void setCondition(S3User user, S3UserStatus status, String value, String reason, String message) {
        status.setObservedGeneration(user.getMetadata().getGeneration());
        status.setConditions(List.of(new ConditionBuilder().withType("Ready").withStatus(value).withReason(reason)
                .withMessage(message == null ? reason : message).withObservedGeneration(user.getMetadata().getGeneration())
                .withLastTransitionTime(transitionTime(status.getConditions(), value, reason)).build()));
        user.setStatus(status);
    }

    private static String failureReason(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.startsWith("S3Backend not found:")) return "BackendNotFound";
        if (message.startsWith("Unsupported S3 provider:")) return "UnsupportedProvider";
        if (message.startsWith("Admin credentials Secret not found")) return "AdminCredentialsNotFound";
        if (message.startsWith("Missing key '")) return "InvalidCredentialsSecret";
        return "ProviderError";
    }

    private static String secretName(S3User user) {
        return user.getSpec().getSecretName() == null || user.getSpec().getSecretName().isBlank()
                ? user.getMetadata().getName() + "-s3" : user.getSpec().getSecretName();
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
        throw new IllegalStateException("Missing key '" + key + "' in Secret " + secret.getMetadata().getName());
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
