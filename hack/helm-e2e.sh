#!/usr/bin/env bash
set -euo pipefail

cluster_name="${KIND_CLUSTER_NAME:-helm-e2e}"
image="${HELM_E2E_IMAGE:-s3-resource-operator:ci}"
release="${HELM_E2E_RELEASE:-s3-resource-operator}"

image_repository="${image%:*}"
image_tag="${image##*:}"

docker build -f src/main/docker/Dockerfile.jvm -t "$image" .
kind load docker-image "$image" --name "$cluster_name"

kubectl apply -f src/test/resources/versitygw.yaml
kubectl rollout status deployment/versitygw --timeout=120s

helm install "$release" charts/s3-resource-operator \
  --set image.repository="$image_repository" \
  --set image.tag="$image_tag" \
  --set image.pullPolicy=IfNotPresent \
  --wait \
  --timeout=120s

deployment="$(kubectl get deployment -l "app.kubernetes.io/instance=${release}" -o jsonpath='{.items[0].metadata.name}')"
service_account="$(kubectl get deployment "$deployment" -o jsonpath='{.spec.template.spec.serviceAccountName}')"
subject="system:serviceaccount:default:${service_account}"

test -n "$deployment"
test -n "$service_account"
test "$(kubectl auth can-i get s3users.s3.dajudge.com --as="$subject")" = yes
test "$(kubectl auth can-i patch s3users.s3.dajudge.com/status --as="$subject")" = yes
test "$(kubectl auth can-i get s3buckets.s3.dajudge.com --as="$subject")" = yes
test "$(kubectl auth can-i patch s3buckets.s3.dajudge.com/status --as="$subject")" = yes
test "$(kubectl auth can-i get s3backends.s3.dajudge.com --as="$subject")" = yes
test "$(kubectl auth can-i get secrets --as="$subject")" = yes
test "$(kubectl auth can-i create secrets --as="$subject")" = yes

kubectl create secret generic helm-e2e-admin \
  --from-literal=accessKey=test-root-access \
  --from-literal=secretKey=test-root-secret

cat <<'EOF' | kubectl apply -f -
apiVersion: s3.dajudge.com/v1alpha1
kind: S3Backend
metadata:
  name: helm-e2e
spec:
  provider: versity
  endpoint: http://versitygw.default.svc.cluster.local:7070
  adminCredentialsSecretRef:
    name: helm-e2e-admin
---
apiVersion: s3.dajudge.com/v1alpha1
kind: S3User
metadata:
  name: helm-e2e
spec:
  backendRef: helm-e2e
---
apiVersion: s3.dajudge.com/v1alpha1
kind: S3Bucket
metadata:
  name: helm-e2e
spec:
  backendRef: helm-e2e
  userRef: helm-e2e
EOF

for resource in s3user/helm-e2e s3bucket/helm-e2e; do
  ready=''
  for _ in $(seq 1 45); do
    ready="$(kubectl get "$resource" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)"
    if [[ "$ready" == 'True' ]]; then
      break
    fi
    sleep 2
  done
  if [[ "$ready" != 'True' ]]; then
    kubectl get "$resource" -o yaml || true
    kubectl logs "deployment/${deployment}" --tail=200 || true
    exit 1
  fi
done

access_key="$(kubectl get secret helm-e2e-s3 -o jsonpath='{.data.accessKey}' | base64 --decode)"
test "$access_key" = 'default.helm-e2e'
