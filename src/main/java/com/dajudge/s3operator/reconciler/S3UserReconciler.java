package com.dajudge.s3operator.reconciler;

import com.dajudge.s3operator.api.S3Instance;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.provider.VersityS3Provider;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@ApplicationScoped
@ControllerConfiguration
public class S3UserReconciler implements Reconciler<S3User> {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    KubernetesClient client;

    @Inject
    VersityS3Provider provider;

    @Override
    public UpdateControl<S3User> reconcile(S3User user, Context<S3User> context) {
        String namespace = user.getMetadata().getNamespace();
        S3Instance instance = client.resources(S3Instance.class)
                .inNamespace(namespace)
                .withName(user.getSpec().getInstanceRef())
                .get();
        if (instance == null) {
            throw new IllegalStateException("S3Instance not found: " + user.getSpec().getInstanceRef());
        }
        if (!provider.type().equals(instance.getSpec().getProvider())) {
            throw new IllegalStateException("Unsupported S3 provider: " + instance.getSpec().getProvider());
        }

        Secret adminSecret = client.secrets().inNamespace(namespace)
                .withName(instance.getSpec().getAdminCredentialsSecretRef().getName())
                .get();
        if (adminSecret == null) {
            throw new IllegalStateException("Admin credentials Secret not found");
        }

        String secretName = user.getSpec().getSecretName() == null || user.getSpec().getSecretName().isBlank()
                ? user.getMetadata().getName() + "-s3"
                : user.getSpec().getSecretName();
        Secret credentials = client.secrets().inNamespace(namespace).withName(secretName).get();
        if (credentials == null) {
            String accessKey = namespace + "." + user.getMetadata().getName();
            String secretKey = randomSecret();
            credentials = new SecretBuilder()
                    .withNewMetadata()
                    .withName(secretName)
                    .withNamespace(namespace)
                    .addNewOwnerReference()
                    .withApiVersion(user.getApiVersion())
                    .withKind(user.getKind())
                    .withName(user.getMetadata().getName())
                    .withUid(user.getMetadata().getUid())
                    .withController(true)
                    .withBlockOwnerDeletion(true)
                    .endOwnerReference()
                    .endMetadata()
                    .addToStringData("accessKey", accessKey)
                    .addToStringData("secretKey", secretKey)
                    .addToStringData("endpoint", instance.getSpec().getEndpoint())
                    .build();
            credentials = client.secrets().resource(credentials).create();
        }

        String accessKey = secretValue(credentials, "accessKey");
        String secretKey = secretValue(credentials, "secretKey");
        provider.createUser(instance.getSpec().getEndpoint(),
                secretValue(adminSecret, "accessKey"), secretValue(adminSecret, "secretKey"),
                accessKey, secretKey, user.getSpec().getRole());

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

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
