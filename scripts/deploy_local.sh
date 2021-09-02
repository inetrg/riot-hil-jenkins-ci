#!/bin/bash
#

Help()
{
    # Display Help
    echo "This script setups up jenkins nodes, generates node configs and deploys docker."
    echo
    echo "Syntax: deploy_local [-h] [-n <int>] [-s <path>] [-d <path>] [-a <string>]"
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

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
COMPOSE_PATH="$SCRIPT_DIR/../docker-compose.yml"

echo "Setting up jenkins nodes on machine"
$SCRIPT_DIR/setup_local_jenkins_agents.sh -n $NUMOF_AGENTS -d $JENKINS_AGENT_HOME_DIR -s $PUBLIC_SSH_KEY_PATH -a $AGENT_NAME
echo "Setting up node configution for docker deployment"
$SCRIPT_DIR/setup_dev_nodes.sh -n $NUMOF_AGENTS -d $JENKINS_AGENT_HOME_DIR -p

echo "Removing old docker instance"
docker-compose -f $COMPOSE_PATH down -v
echo "Starting new docker instance"
docker-compose -f $COMPOSE_PATH up --build -d
