FROM jenkins/jenkins:lts-jdk21

USER root

# JDK 8 + Maven, used only to build PiggyMetrics (Spring Boot 2.0 needs Java 8).
# Jenkins itself keeps running on its own JDK 21.
COPY --from=maven:3.9-eclipse-temurin-8 /opt/java/openjdk /opt/jdk8
COPY --from=maven:3.9-eclipse-temurin-8 /usr/share/maven /opt/maven
RUN printf '#!/bin/sh\nJAVA_HOME=/opt/jdk8 exec /opt/maven/bin/mvn "$@"\n' > /usr/local/bin/mvn \
 && chmod +x /usr/local/bin/mvn

# Docker CLI + buildx: builds and pushes images through the host's Docker daemon (mounted socket)
COPY --from=docker:29-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker:29-cli /usr/local/libexec/docker/cli-plugins/docker-buildx /usr/local/libexec/docker/cli-plugins/docker-buildx

# kubectl, same version as the kind cluster
ARG KUBECTL_VERSION=v1.37.0
RUN curl -fsSLo /usr/local/bin/kubectl \
      "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/$(dpkg --print-architecture)/kubectl" \
 && chmod +x /usr/local/bin/kubectl

USER jenkins
