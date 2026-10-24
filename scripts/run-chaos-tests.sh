#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-staging-environment}"

echo "==> [1/3] Applying 50ms TCP network latency injection (Chaos Mesh)..."
kubectl apply -f k8s/chaos/chaos-network-latency.yaml
kubectl get networkchaos -n "${NAMESPACE}"

echo "==> [2/3] Verifying L1 local JVM cache throughput under latency..."
kubectl exec -n "${NAMESPACE}" deploy/bloom-sync-service -- \
    wget -qO- http://localhost:8080/actuator/health

echo "==> [3/3] Applying 30s bidirectional network partition (Chaos Mesh)..."
kubectl apply -f k8s/chaos/chaos-network-partition.yaml
echo "==> Chaos Mesh experiments active. Monitor Grafana dashboard for L1/L2 lag recovery."
