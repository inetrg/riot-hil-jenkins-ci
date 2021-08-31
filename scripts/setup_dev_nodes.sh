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
    echo "Syntax: setup_local_jenkins_agents [-h] "
    echo "options:"
    echo "h                             Print this Help."
    echo "p                             Prompt for boards and ports."
    echo "n     NUMOF_AGENTS            Number of agents to instantiate, defaults to 1"
    echo "d     JENKINS_AGENT_HOME_DIR  The home directory for the agent user, defaults to \"/srv/jenkins\""
}

PROMPT_OPT=0
NUMOF_AGENTS=1
JENKINS_AGENT_HOME_DIR='/srv/jenkins'

while getopts "hpn:d:" option; do
    case $option in
        p) # prompt for inputs
            PROMPT_OPT=1
            ;;
        n) # override num of agents
            NUMOF_AGENTS=${OPTARG}
            ;;
        d) # override home dir of jenkins
            JENKINS_AGENT_HOME_DIR=${OPTARG}
            ;;
        h | *) # display Help
            Help
            exit 0;;
    esac
done

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
LOCAL_CONF_DIR="${SCRIPT_DIR}/../casc_configs/local"

echo "LOCAL_CONF_DIR= ${LOCAL_CONF_DIR}"
echo "NUMOF_AGENTS=${NUMOF_AGENTS}"
echo "JENKINS_AGENT_HOME_DIR=${JENKINS_AGENT_HOME_DIR}"

mkdir -p $LOCAL_CONF_DIR
cat "$SCRIPT_DIR/build_node_template.yaml" > "$LOCAL_CONF_DIR/dev_build_node.yaml"
sed -i "s#<JENKINS_AGENT_HOME_DIR>#$JENKINS_AGENT_HOME_DIR#g" "$LOCAL_CONF_DIR/dev_build_node.yaml"

if [ -n "$NUMOF_AGENTS" ] && [ "$NUMOF_AGENTS" -eq "$NUMOF_AGENTS" ] 2>/dev/null; then
    for ((i=0;i<$NUMOF_AGENTS;i++))
    do
        cat "$SCRIPT_DIR/test_node_template.yaml" > "$LOCAL_CONF_DIR/dev_test_node_${i}.yaml"
        sed -i "s#<JENKINS_AGENT_HOME_DIR>#$JENKINS_AGENT_HOME_DIR#g" "$LOCAL_CONF_DIR/dev_test_node_${i}.yaml"
        sed -i "s#<AGENT_NUMBER>#$i#g" "$LOCAL_CONF_DIR/dev_test_node_${i}.yaml"
        if [ "$PROMPT_OPT" = "1" ]; then
            read -e -p "Test node $i board (samr21-xpro): " my_board
            read -e -p "Test node $i board port (/dev/ttyACM0): " board_port
            read -e -p "Test node $i PHiLIP port (/dev/ttyACM1): " philip_port
            my_board=${my_board:-"samr21-xpro"}
            board_port=${board_port:-"/dev/ttyACM0"}
            philip_port=${philip_port:-"/dev/ttyACM1"}
            sed -i "s#<MY_BOARD>#$my_board#g" "$LOCAL_CONF_DIR/dev_test_node_${i}.yaml"
            sed -i "s#<BOARD_PORT>#$board_port#g" "$LOCAL_CONF_DIR/dev_test_node_${i}.yaml"
            sed -i "s#<PHILIP_PORT>#$philip_port#g" "$LOCAL_CONF_DIR/dev_test_node_${i}.yaml"
        fi
    done

else
    echo "NUMOF_AGENTS (${NUMOF_AGENTS}) must be a number!"
    exit 1;
fi
