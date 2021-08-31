#!/bin/bash
#
# This script sets up local jenkins agents.
# The intention is to allow testing of the jenkins deployment with boards
# that are just plugging into the developer machine.
#

Help()
{
    # Display Help
    echo "Deploys a ."
    echo
    echo "Syntax: setup_local_jenkins_agents [-h] "
    echo "options:"
    echo "h                             Print this Help."
    echo "n     NUMOF_AGENTS            Number of agents to instantiate, defaults to 8"
}

NUMOF_AGENTS=8
JENKINS_AGENT_HOME_DIR='/opt/jenkins'

while getopts "hn:" option; do
    case $option in
        n) # override num of agents
            NUMOF_AGENTS=${OPTARG}
            ;;
        h | *) # display Help
            Help
            exit 0;;
    esac
done

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
PROD_CONF_DIR="${SCRIPT_DIR}/../casc_configs/prod"

echo "PROD_CONF_DIR= ${PROD_CONF_DIR}"
echo "NUMOF_AGENTS=${NUMOF_AGENTS}"

read -e -p "Enter rack number (0): " rack_num
RACK_NUM=${rack_num:-0}
FILENAME="$PROD_CONF_DIR/haw_nodes_rack_$RACK_NUM.yaml"

echo "jenkins:" > $FILENAME
echo "  nodes:" >> $FILENAME

if [ -n "$NUMOF_AGENTS" ] && [ "$NUMOF_AGENTS" -eq "$NUMOF_AGENTS" ] 2>/dev/null; then
    for ((i=1;i<$NUMOF_AGENTS;i++))
    do
        read -e -p "Test node $i board (samr21-xpro): " my_board
        read -e -p "Can PHiLIP reset the DUT? [Y/n]: " add_reset
        my_board=${my_board:-"samr21-xpro"}
        add_reset=${add_reset:-"Y"}
        echo "  - permanent:" >> $FILENAME
        echo "      numExecutors: 1" >> $FILENAME
        echo "      labelString: \"HIL ${my_board} raspbian\"" >> $FILENAME
        echo "      launcher:" >> $FILENAME
        echo "        ssh:" >> $FILENAME
        echo "          credentialsId: \"jenkins_master_ssh\"" >> $FILENAME
        echo "          host: \"10.28.48.${RACK_NUM}${i}\"" >> $FILENAME
        echo "          port: 22" >> $FILENAME
        echo "          sshHostKeyVerificationStrategy: \"nonVerifyingKeyVerificationStrategy\"" >> $FILENAME
        echo "      name: \"r${RACK_NUM}p${i}\"" >> $FILENAME
        echo "      nodeDescription: \"The node in position ${i} of rack ${RACK_NUM} in the HiL Server Room\"" >> $FILENAME
        echo "      nodeProperties:" >> $FILENAME
        echo "      - envVars:" >> $FILENAME
        echo "          env:" >> $FILENAME
        echo "          - key: \"BOARD\"" >> $FILENAME
        echo "            value: \"${my_board}\"" >> $FILENAME
        echo "          - key: \"PHILIP_PORT\"" >> $FILENAME
        echo "            value: \"/dev/hil/philip\"" >> $FILENAME
        echo "          - key: \"PORT\"" >> $FILENAME
        echo "            value: \"/dev/hil/dut\"" >> $FILENAME
        if [[ $add_reset == "Y" || $add_reset == "y" ]]; then
            echo "          - key: \"HIL_RESET_WAIT\"" >> $FILENAME
            echo "            value: 0" >> $FILENAME
            echo "          - key: \"RESET\"" >> $FILENAME
            echo "            value: \"python3 -m philip_pal --dut_reset\"" >> $FILENAME
            echo "          - key: \"RESET_FLAGS\"" >> $FILENAME
            echo "            value: \"/dev/hil/philip\"" >> $FILENAME
        fi
        echo "          - key: \"RIOTBASE\"" >> $FILENAME
        echo "            value: \"/opt/RIOT\"" >> $FILENAME
        echo "      remoteFS: \"/opt/jenkins\"" >> $FILENAME
        echo "      retentionStrategy: \"always\"" >> $FILENAME
    done

else
    echo "NUMOF_AGENTS (${NUMOF_AGENTS}) must be a number!"
    exit 1;
fi
