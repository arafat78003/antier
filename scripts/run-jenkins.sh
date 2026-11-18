#!/usr/bin/env bash
# Builds the Jenkins image and runs Jenkins in Docker, connected to the kind cluster.
# Run after the kind cluster exists. Safe to re-run: jobs and settings live in the jenkins_home volume.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLUSTER_NAME="${CLUSTER_NAME:-piggymetrics}"
JENKINS_PORT="${JENKINS_PORT:-8080}"
CONTAINER_NAME="piggymetrics-jenkins"
IMAGE_NAME="piggymetrics-jenkins"

if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
  echo "kind cluster '$CLUSTER_NAME' not found. Create it first:" >&2
  echo "  kind create cluster --name $CLUSTER_NAME --config scripts/kind-config.yaml" >&2
  exit 1
fi

echo "==> Building Jenkins image (JDK 8 + Maven, Docker CLI, kubectl)"
docker build -t "$IMAGE_NAME" -f "$SCRIPT_DIR/jenkins.Dockerfile" "$SCRIPT_DIR"

if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER_NAME"; then
  echo "==> Replacing existing $CONTAINER_NAME container"
  docker rm -f "$CONTAINER_NAME" >/dev/null
fi

# The group that owns the Docker socket, as seen from inside a container (works on Linux and Docker Desktop).
DOCKER_SOCKET_GID="$(docker run --rm --user root --entrypoint stat \
  -v /var/run/docker.sock:/var/run/docker.sock "$IMAGE_NAME" -c '%g' /var/run/docker.sock)"

echo "==> Starting Jenkins on http://localhost:$JENKINS_PORT"
# --network kind: Jenkins reaches the cluster API by its container name, with normal TLS verification.
docker run -d --name "$CONTAINER_NAME" --restart unless-stopped \
  --network kind \
  -p "$JENKINS_PORT:8080" \
  -v jenkins_home:/var/jenkins_home \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --group-add "$DOCKER_SOCKET_GID" \
  "$IMAGE_NAME" >/dev/null

echo "==> Giving Jenkins the kind cluster credentials"
# Written straight into the container's home volume; nothing is stored in the repo.
kind get kubeconfig --name "$CLUSTER_NAME" --internal \
  | docker exec -i "$CONTAINER_NAME" sh -c 'mkdir -p ~/.kube && cat > ~/.kube/config && chmod 600 ~/.kube/config'

echo "==> Waiting for Jenkins to come up"
for _ in $(seq 1 60); do
  status="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$JENKINS_PORT/login" || true)"
  if [ "$status" = "200" ] || [ "$status" = "403" ]; then
    break
  fi
  sleep 3
done

echo
echo "Jenkins is running at http://localhost:$JENKINS_PORT"
if docker exec "$CONTAINER_NAME" test -f /var/jenkins_home/secrets/initialAdminPassword; then
  echo "Initial admin password: $(docker exec "$CONTAINER_NAME" cat /var/jenkins_home/secrets/initialAdminPassword)"
fi
