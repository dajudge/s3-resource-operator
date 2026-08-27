package com.dajudge.s3operator.reconciler;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.ADMIN_CREDENTIALS_NOT_FOUND;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.BACKEND_NOT_FOUND;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.INVALID_CREDENTIALS_SECRET;
import static com.dajudge.s3operator.reconciler.ReconciliationException.Reason.UNSUPPORTED_PROVIDER;

final class ReconcilerSupport {
    private ReconcilerSupport() {}

    static S3Backend requireBackend(KubernetesClient client, VersityS3Provider provider, String namespace, String name) {
        S3Backend backend = client.resources(S3Backend.class).inNamespace(namespace).withName(name).get();
        if (backend == null) throw new ReconciliationException(BACKEND_NOT_FOUND, "S3Backend not found: " + name);
        ResourceValidation.validateBackend(backend);
        if (!provider.type().equals(backend.getSpec().getProvider())) {
            throw new ReconciliationException(UNSUPPORTED_PROVIDER,
                    "Unsupported S3 provider: " + backend.getSpec().getProvider());
        }
        return backend;
    }

    static Secret requireAdminSecret(KubernetesClient client, String namespace, S3Backend backend) {
        Secret secret = client.secrets().inNamespace(namespace)
                .withName(backend.getSpec().getAdminCredentialsSecretRef().getName()).get();
        if (secret == null) {
            throw new ReconciliationException(ADMIN_CREDENTIALS_NOT_FOUND, "Admin credentials Secret not found");
        }
        return secret;
    }

    static String transitionTime(List<Condition> conditions, String status, String reason) {
        if (conditions != null) {
            for (Condition condition : conditions) {
                if ("Ready".equals(condition.getType()) && status.equals(condition.getStatus())
                        && reason.equals(condition.getReason()) && condition.getLastTransitionTime() != null) {
                    return condition.getLastTransitionTime();
                }
            }
        }
        return Instant.now().toString();
    }

    static String secretValue(Secret secret, String key) {
        if (secret.getData() != null && secret.getData().containsKey(key)) {
            return new String(Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
        }
        if (secret.getStringData() != null && secret.getStringData().containsKey(key)) {
            return secret.getStringData().get(key);
        }
        throw new ReconciliationException(INVALID_CREDENTIALS_SECRET,
                "Missing key '" + key + "' in Secret " + secret.getMetadata().getName());
    }
}
