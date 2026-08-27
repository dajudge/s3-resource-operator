#!/usr/bin/env bash
set -euo pipefail

version="${PUBLISHED_VERSION:?PUBLISHED_VERSION must be set, e.g. 0.1.0-rc.7}"
cluster_name="${KIND_CLUSTER_NAME:-published-release-e2e}"
namespace="${E2E_NAMESPACE:-published-release-e2e}"
release="${E2E_RELEASE:-s3-resource-operator}"
chart="oci://registry-1.docker.io/dajudge/s3-resource-operator-chart"
image_repository="dajudge/s3-resource-operator"

wait_ready() {
  local resource="$1"
  local ready=''
  for _ in $(seq 1 60); do
    ready="$(kubectl get -n "$namespace" "$resource" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)"
    if [[ "$ready" == 'True' ]]; then
      return 0
    fi
    sleep 2
  done
  kubectl get -n "$namespace" "$resource" -o yaml || true
  return 1
}

dump_diagnostics() {
  helm status "$release" -n "$namespace" || true
  kubectl get all -n "$namespace" -o wide || true
  kubectl get s3backends,s3users,s3buckets -n "$namespace" -o yaml || true
  kubectl logs -n "$namespace" -l "app.kubernetes.io/instance=${release}" --all-containers --tail=200 || true
}

trap 'rc=$?; if [[ $rc -ne 0 ]]; then dump_diagnostics; fi; exit $rc' EXIT

kubectl create namespace "$namespace"
kubectl apply -n "$namespace" -f src/test/resources/versitygw.yaml
kubectl rollout status -n "$namespace" deployment/versitygw --timeout=120s

helm install "$release" "$chart" \
  --version "$version" \
  --namespace "$namespace" \
  --wait \
  --timeout=180s

deployment="$(kubectl get deployment -n "$namespace" -l "app.kubernetes.io/instance=${release}" -o jsonpath='{.items[0].metadata.name}')"
test -n "$deployment"
test "$(kubectl get deployment -n "$namespace" "$deployment" -o jsonpath='{.spec.template.spec.containers[?(@.name=="operator")].image}')" = "${image_repository}:${version}"

kubectl create secret generic published-e2e-admin -n "$namespace" \
  --from-literal=accessKey=test-root-access \
  --from-literal=secretKey=test-root-secret

cat <<EOF | kubectl apply -n "$namespace" -f -
apiVersion: s3.dajudge.com/v1alpha1
kind: S3Backend
metadata:
  name: published-e2e
spec:
  provider: versity
  endpoint: http://versitygw.${namespace}.svc.cluster.local:7070
  adminCredentialsSecretRef:
    name: published-e2e-admin
---
apiVersion: s3.dajudge.com/v1alpha1
kind: S3User
metadata:
  name: published-e2e
spec:
  backendRef: published-e2e
---
apiVersion: s3.dajudge.com/v1alpha1
kind: S3Bucket
metadata:
  name: published-e2e
spec:
  backendRef: published-e2e
  userRef: published-e2e
  deletionPolicy: DELETE
EOF

wait_ready s3user/published-e2e
wait_ready s3bucket/published-e2e

user_uid_before="$(kubectl get s3user published-e2e -n "$namespace" -o jsonpath='{.metadata.uid}')"
bucket_uid_before="$(kubectl get s3bucket published-e2e -n "$namespace" -o jsonpath='{.metadata.uid}')"
access_before="$(kubectl get secret published-e2e-s3 -n "$namespace" -o jsonpath='{.data.accessKey}')"
secret_before="$(kubectl get secret published-e2e-s3 -n "$namespace" -o jsonpath='{.data.secretKey}')"

test -n "$access_before"
test -n "$secret_before"

# Upgrade the installed published RC to the chart currently under test while keeping
# the already-published, tested operator image. This exercises Helm/CRD upgrade safety
# without introducing an unpublished runtime binary into the validation.
helm upgrade "$release" charts/s3-resource-operator \
  --namespace "$namespace" \
  --set image.repository="$image_repository" \
  --set image.tag="$version" \
  --set image.pullPolicy=IfNotPresent \
  --wait \
  --timeout=180s

kubectl rollout status -n "$namespace" deployment/"$deployment" --timeout=120s

test "$(kubectl get s3user published-e2e -n "$namespace" -o jsonpath='{.metadata.uid}')" = "$user_uid_before"
test "$(kubectl get s3bucket published-e2e -n "$namespace" -o jsonpath='{.metadata.uid}')" = "$bucket_uid_before"
test "$(kubectl get secret published-e2e-s3 -n "$namespace" -o jsonpath='{.data.accessKey}')" = "$access_before"
test "$(kubectl get secret published-e2e-s3 -n "$namespace" -o jsonpath='{.data.secretKey}')" = "$secret_before"

wait_ready s3user/published-e2e
wait_ready s3bucket/published-e2e

test "$(kubectl get crd s3backends.s3.dajudge.com -o jsonpath='{.metadata.name}')" = 's3backends.s3.dajudge.com'
test "$(kubectl get crd s3users.s3.dajudge.com -o jsonpath='{.metadata.name}')" = 's3users.s3.dajudge.com'
test "$(kubectl get crd s3buckets.s3.dajudge.com -o jsonpath='{.metadata.name}')" = 's3buckets.s3.dajudge.com'

kubectl delete s3bucket published-e2e -n "$namespace" --wait=true --timeout=120s
kubectl delete s3user published-e2e -n "$namespace" --wait=true --timeout=120s

echo "Published release ${version} install and upgrade validation passed."
