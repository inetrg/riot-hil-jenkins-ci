FROM jenkins/jenkins:2.289.3-lts

USER root

RUN apt-get update && apt-get install -y \
    xsltproc \
    python3 \
    python3-pip \
    python3-setuptools \
    vim

RUN pip3 install --no-cache-dir gitpython

USER jenkins

ENV JAVA_OPTS -Djenkins.install.runSetupWizard=false

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
# RUN jenkins-plugin-cli -f /usr/share/jenkins/ref/plugins.txt
# Though they recommend using the plugin cli it sometimes freezes on poor networks so we can fallback to the old tried and true
RUN /usr/local/bin/install-plugins.sh < /usr/share/jenkins/ref/plugins.txt

ENV CASC_JENKINS_CONFIG /var/casc_configs
ARG CASC_ENV="local"
ENV CASC_ENV=$CASC_ENV

COPY casc_configs/common "casc_configs/${CASC_ENV}" "${CASC_JENKINS_CONFIG}/"

ENV GITHUB_UID=833237cca5df400faa29a10178c1d44f
ENV GITHUB_CI_USERNAME="riot-hil-bot"
ENV JENKINS_URL="https://localhost:8080"
ENV JENKINS_OPTS="--prefix="
ENV LIB_VERSION="main"

ENV SECRETS /run/secrets
