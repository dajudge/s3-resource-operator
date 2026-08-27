#!/usr/bin/env bash
set -euo pipefail

cluster_name="${KIND_CLUSTER_NAME:-helm-e2e}"
image="${HELM_E2E_IMAGE:-s3-resource-operator:ci}"
release="${HELM_E2E_RELEASE:-s3-resource-operator}"
namespace="${HELM_E2E_NAMESPACE:-helm-e2e}"
service_account="${HELM_E2E_SERVICE_ACCOUNT:-helm-e2e-operator}"
resync_interval="${HELM_E2E_RESYNC_INTERVAL:-17s}"

image_repository="${image%:*}"
image_tag="${image##*:}"

dump_diagnostics() {
  echo '--- Helm release ---'
  helm status "$release" --namespace "$namespace" || true
  echo '--- Deployments ---'
  kubectl get deployments -n "$namespace" -o wide || true
  echo '--- Pods ---'
  kubectl get pods -n "$namespace" -o wide || true
  echo '--- Pod descriptions ---'
  kubectl describe pods -n "$namespace" -l "app.kubernetes.io/instance=${release}" || true
  echo '--- Operator logs ---'
  kubectl logs -n "$namespace" -l "app.kubernetes.io/instance=${release}" --all-containers --tail=200 || true
  echo '--- Recent events ---'
  kubectl get events -n "$namespace" --sort-by=.lastTimestamp | tail -100 || true
}

docker build -f src/main/docker/Dockerfile.jvm -t "$image" .
kind load docker-image "$image" --name "$cluster_name"

kubectl create namespace "$namespace"
kubectl create serviceaccount "$service_account" -n "$namespace"
kubectl apply -n "$namespace" -f src/test/resources/versitygw.yaml
kubectl rollout status -n "$namespace" deployment/versitygw --timeout=120s

if ! helm install "$release" charts/s3-resource-operator \
  --namespace "$namespace" \
  --set serviceAccount.create=false \
  --set serviceAccount.name="$service_account" \
  --set image.repository="$image_repository" \
  --set image.tag="$image_tag" \
  --set image.pullPolicy=IfNotPresent \
  --set reconciliation.resyncInterval="$resync_interval" \
  --wait \
  --timeout=120s; then
  dump_diagnostics
  exit 1
fi

deployment="$(kubectl get deployment -n "$namespace" -l "app.kubernetes.io/instance=${release}" -o jsonpath='{.items[0].metadata.name}')"
configured_service_account="$(kubectl get deployment -n "$namespace" "$deployment" -o jsonpath='{.spec.template.spec.serviceAccountName}')"
configured_image="$(kubectl get deployment -n "$namespace" "$deployment" -o jsonpath='{.spec.template.spec.containers[?(@.name=="operator")].image}')"
subject="system:serviceaccount:${namespace}:${service_account}"
configured_resync="$(kubectl get deployment -n "$namespace" "$deployment" -o jsonpath='{.spec.template.spec.containers[?(@.name=="operator")].env[?(@.name=="S3_OPERATOR_RESYNC_INTERVAL")].value}')"

test -n "$deployment"
test "$configured_service_account" = "$service_account"
test "$configured_image" = "$image"
test "$configured_resync" = "$resync_interval"
test "$(kubectl get serviceaccount -n "$namespace" "$service_account" -o jsonpath='{.metadata.name}')" = "$service_account"
test "$(kubectl auth can-i get s3users.s3.dajudge.com --as="$subject" -n "$namespace")" = yes
test "$(kubectl auth can-i patch s3users.s3.dajudge.com/status --as="$subject" -n "$namespace")" = yes
test "$(kubectl auth can-i get s3buckets.s3.dajudge.com --as="$subject" -n "$namespace")" = yes
test "$(kubectl auth can-i patch s3buckets.s3.dajudge.com/status --as="$subject" -n "$namespace")" = yes
test "$(kubectl auth can-i get s3backends.s3.dajudge.com --as="$subject" -n "$namespace")" = yes
test "$(kubectl auth can-i get customresourcedefinitions.apiextensions.k8s.io --as="$subject")" = yes
test "$(kubectl auth can-i get secrets --as="$subject" -n "$namespace")" = yes
test "$(kubectl auth can-i create secrets --as="$subject" -n "$namespace")" = yes

kubectl create secret generic helm-e2e-admin -n "$namespace" \
  --from-literal=accessKey=test-root-access \
  --from-literal=secretKey=test-root-secret

cat <<EOF | kubectl apply -n "$namespace" -f -
apiVersion: s3.dajudge.com/v1alpha1
kind: S3Backend
metadata:
  name: helm-e2e
spec:
  provider: versity
  endpoint: http://versitygw.${namespace}.svc.cluster.local:7070
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
    ready="$(kubectl get -n "$namespace" "$resource" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)"
    if [[ "$ready" == 'True' ]]; then
      break
    fi
    sleep 2
  done
  if [[ "$ready" != 'True' ]]; then
    kubectl get -n "$namespace" "$resource" -o yaml || true
    dump_diagnostics
    exit 1
  fi
done

access_key="$(kubectl get secret -n "$namespace" helm-e2e-s3 -o jsonpath='{.data.accessKey}' | base64 --decode)"
test "$access_key" = "${namespace}.helm-e2e"
