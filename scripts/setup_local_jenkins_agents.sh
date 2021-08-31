#!/bin/bash
#
# This script sets up local jenkins agents.
# The intention is to allow testing of the jenkins deployment with boards
# that are just plugging into the developer machine.
#

Help()
{
    # Display Help
    echo "Setup script to add local jenkins agents for testing local deployment."
    echo
    echo "Syntax: setup_local_jenkins_agents [-h] [-n <int>] [-s <path>] [-d <path>] [-a <string>]"
    echo "options:"
    echo "h                             Print this Help."
    echo "s     PUBLIC_SSH_KEY_PATH     Path to the public key for authorized keys, \
defaults to \"${HOME}/.ssh/id_rsa.pub\"."
    echo "n     NUMOF_AGENTS            Number of agents to instantiate, defaults to 1"
    echo "d     JENKINS_AGENT_HOME_DIR  The home directory for the agent user, defaults to \"/srv/jenkins\""
    echo "a     AGENT_NAME              The agent username, detaults to \"jenkins\""
}

PUBLIC_SSH_KEY_PATH="${HOME}/.ssh/id_rsa.pub"
NUMOF_AGENTS=1
JENKINS_AGENT_HOME_DIR='/srv/jenkins'
AGENT_NAME='jenkins'

while getopts "hpn:d:s:a:" option; do
    case $option in
        n) # override num of agents
            NUMOF_AGENTS=${OPTARG}
            ;;
        d) # override home dir of jenkins
            JENKINS_AGENT_HOME_DIR=${OPTARG}
            ;;
        s) # override ssh key path
            PUBLIC_SSH_KEY_PATH=${OPTARG}
            ;;
        a) # override agent name
            AGENT_NAME=${OPTARG}
            ;;
        h | *) # display Help
            Help
            exit 0;;
    esac
done

echo "PUBLIC_SSH_KEY_PATH=${PUBLIC_SSH_KEY_PATH}"
echo "NUMOF_AGENTS=${NUMOF_AGENTS}"
echo "JENKINS_AGENT_HOME_DIR=${JENKINS_AGENT_HOME_DIR}"
echo "AGENT_NAME=${AGENT_NAME}"

sudo mkdir -p ${JENKINS_AGENT_HOME_DIR}
sudo useradd -d ${JENKINS_AGENT_HOME_DIR} ${AGENT_NAME}
sudo usermod -a -G dialout,docker,plugdev,users ${AGENT_NAME}

# Setup ssh permissions
sudo mkdir -p ${JENKINS_AGENT_HOME_DIR}/.ssh
sudo cat ${PUBLIC_SSH_KEY_PATH} | sudo dd of=${JENKINS_AGENT_HOME_DIR}/.ssh/authorized_keys
sudo chmod 700 ${JENKINS_AGENT_HOME_DIR}/.ssh
sudo chmod 644 ${JENKINS_AGENT_HOME_DIR}/.ssh/authorized_keys

if [ -n "$NUMOF_AGENTS" ] && [ "$NUMOF_AGENTS" -eq "$NUMOF_AGENTS" ] 2>/dev/null; then
    for ((i=0;i<$NUMOF_AGENTS;i++))
    do
        sudo mkdir -p ${JENKINS_AGENT_HOME_DIR}/slave_$i
    done

else
    echo "NUMOF_AGENTS (${NUMOF_AGENTS}) must be a number!"
    exit 1
fi

sudo apt install -y openjdk-8-jre
sudo git clone https://github.com/RIOT-OS/RIOT.git ${JENKINS_AGENT_HOME_DIR}/RIOT
sudo chown -R ${AGENT_NAME}:${AGENT_NAME} ${JENKINS_AGENT_HOME_DIR}