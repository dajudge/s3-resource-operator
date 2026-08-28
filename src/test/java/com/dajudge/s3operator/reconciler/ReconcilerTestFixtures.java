package com.dajudge.s3operator.reconciler;

import com.dajudge.s3operator.api.S3Backend;
import com.dajudge.s3operator.api.S3BackendSpec;
import com.dajudge.s3operator.api.S3Bucket;
import com.dajudge.s3operator.api.S3BucketSpec;
import com.dajudge.s3operator.api.S3User;
import com.dajudge.s3operator.api.S3UserSpec;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

final class ReconcilerTestFixtures {
    static final String NS = "ns";
    static final String ENDPOINT = "http://versity:7070";

    private ReconcilerTestFixtures() {}

    static S3Backend backend() {
        LocalObjectReference ref = new LocalObjectReference();
        ref.setName("admin");
        S3BackendSpec spec = new S3BackendSpec();
        spec.setProvider("versity");
        spec.setEndpoint(ENDPOINT);
        spec.setAdminCredentialsSecretRef(ref);
        S3Backend backend = new S3Backend();
        backend.setSpec(spec);
        return backend;
    }

    static S3User user(String name, String backendRef, String secretName) {
        S3UserSpec spec = new S3UserSpec();
        spec.setBackendRef(backendRef);
        spec.setSecretName(secretName);
        S3User user = new S3User();
        user.setApiVersion("s3.dajudge.com/v1alpha1");
        user.setKind("S3User");
        user.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(NS)
                .withUid("uid")
                .withGeneration(7L)
                .build());
        user.setSpec(spec);
        return user;
    }

    static S3Bucket bucket(
            String name,
            String backendRef,
            String userRef,
            String bucketName,
            S3BucketSpec.DeletionPolicy deletionPolicy) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef(backendRef);
        spec.setUserRef(userRef);
        spec.setBucketName(bucketName);
        spec.setDeletionPolicy(deletionPolicy);
        S3Bucket bucket = new S3Bucket();
        bucket.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(NS)
                .withGeneration(9L)
                .build());
        bucket.setSpec(spec);
        return bucket;
    }

    static S3Bucket bucketReferencing(String userName) {
        S3BucketSpec spec = new S3BucketSpec();
        spec.setBackendRef("backend");
        spec.setUserRef(userName);
        S3Bucket bucket = new S3Bucket();
        bucket.setSpec(spec);
        return bucket;
    }

    static Secret secret(String name, String accessKey, String secretKey) {
        return new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .endMetadata()
                .addToStringData("accessKey", accessKey)
                .addToStringData("secretKey", secretKey)
                .build();
    }

    static Secret userSecret(String name) {
        return secret(name, "alice-access", "alice-secret");
    }

    static Secret adminSecret() {
        return secret("admin", "admin-access", "admin-secret");
    }
}
