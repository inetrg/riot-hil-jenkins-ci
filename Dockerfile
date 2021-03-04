FROM jenkins/jenkins:2.263.4-lts

USER root

RUN apt-get update && apt-get install -y \
    xsltproc \
    python3 \
    python3-pip \
    python3-setuptools

USER jenkins

ENV JAVA_OPTS -Djenkins.install.runSetupWizard=false

ENV SECRETS /run/secrets

ARG GITHUB_OAUTH_CLIENT_ID=9c40627c9ba07be2578a
ENV GITHUB_OAUTH_CLIENT_ID=$GITHUB_OAUTH_CLIENT_ID

ARG ADMIN_0=MrKevinWeiss
ENV ADMIN_0=$ADMIN_0

ARG ADMIN_1=leandrolanzieri
ENV ADMIN_1=$ADMIN_1

ARG ADMIN_2=cgundogan
ENV ADMIN_2=$ADMIN_2

ARG GITHUB_UID=231643d03aef11ebadc10242ac120002
ENV GITHUB_UID=$GITHUB_UID

ARG GITHUB_REPO_OWNER=RIOT-OS
ENV GITHUB_REPO_OWNER=$GITHUB_REPO_OWNER

ARG GITHUB_CI_USERNAME=riot-ci
ENV GITHUB_CI_USERNAME=$GITHUB_CI_USERNAME

ARG JENKINS_URL="https://ci.riot-os.org/hil/"
ENV JENKINS_URL=$JENKINS_URL

ARG JENKINS_PREFIX="hil"
ENV JENKINS_OPTS="--prefix=/${JENKINS_PREFIX}"

ENV CASC_JENKINS_CONFIG /var/casc_configs

ARG CASC_ENV="live"
ENV CASC_ENV=$CASC_ENV

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
# RUN jenkins-plugin-cli -f /usr/share/jenkins/ref/plugins.txt
# Though they recommend using the plugin cli it sometimes freezes on poor networks so we can fallback to the old tried and true
RUN /usr/local/bin/install-plugins.sh < /usr/share/jenkins/ref/plugins.txt

COPY casc_configs/common "casc_configs/${CASC_ENV}/*" "${CASC_JENKINS_CONFIG}/"
