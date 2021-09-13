/* This file uses both decalritive syntax and scripted.
 * This is because delaritive is simpler and generally preferred but suffer
 * from limited dynamic discovery and implementations.
 */

import jenkins.model.*

/* globals ================================================================== */
/* Global variables are decalred without `def` as they must be used in both
 * declaritive and scripted mode */
collectBuilders = [:]
boardTestQueue = [].asSynchronized()
totalResults = [:].asSynchronized()
nodeBoards = []

/* pipeline ================================================================= */
pipeline {
    libraries {
        lib('inet_hil_ci_utils')
    }
    agent { label 'master' }
    options {
        // If the whole process takes more than x hours then exit
        // This must be longer since multiple jobs can be started but waiting on nodes to complete
        timeout(time: 3, unit: 'HOURS')
        // Failing fast allows the nodes to be interrupted as some steps can take a while
        parallelsAlwaysFailFast()
    }
    stages {
        stage('setup master') {
            steps {
                script{
                    stepCheckoutRIOT()
                    stepFillBoardTestQueue()
                    stepStashRIOT()
                }
            }
        }
        stage('setup build server and build') {
            steps {
                script {
                    processBuilderTask()
                    parallel collectBuilders
                }
            }
        }
        stage('node test') {
            steps {
                runParallel items: nodeBoards.collect { "${it}" }
            }
        }
        stage('notify if PR') {
            steps {
                stepNotify()
            }
        }
    }
}

/* master steps ============================================================= */
def stepCheckoutRIOT() {
    common.helperCheckoutRepo(params.RIOT_URL,
                              params.RIOT_PR,
                              params.RIOT_BRANCH,
                              ".",
                              params.RIOT_OWNER,
                              "RIOT")
}

def stepFillBoardTestQueue() {
    nodeBoards = common.getBoardsFromNodes(params.HIL_BOARDS)
    tests = common.getTests(params.HIL_TESTS)
    totalResults = common.getEmptyResultsFromBoards(nodeBoards)
    boardTestQueue = common.getBoardTestQueue(nodeBoards, tests)
}

def stepStashRIOT() {
    stash name: "RiotRepo"
}

def stepNotify() {
    if (params.RIOT_PR != "") {
        msg = common.generateNotifyMsgMD(totalResults)
        common.notifyOnPR(params.RIOT_OWNER, "RIOT", params.RIOT_PR, msg)
    }
}

/* riot_build steps =============================================================== */
def buildOnBuilder(String agentName) {
    node("${agentName}") {
        stage("Building on ${agentName}") {
            stepCheckoutRIOT()
            stepBuildJobs()
        }
    }
}

def processBuilderTask() {
   for(builder in common.getActiveBuildNodes()) {
       def agentName = builder
        println "Preparing task for " + agentName
        collectBuilders["Build on " + agentName] = {
            buildOnBuilder(agentName)
        }
    }
}

def stepBuildJobs() {
    common.buildJobs(boardTestQueue, totalResults, params.EXTRA_MAKE_COMMANDS)
}

/* test node steps ========================================================== */
def stepUnstashRIOT() {
    unstash name: "RiotRepo"
}

/* Runs all tests on each board in parallel. */
def runParallel(args) {
    parallel args.items.collectEntries { name -> [ "${name}": {

        node (name) {
            stage("${name}") {
                /* We want to timeout a node if it doesn't respond
                 * The timeout should only start once it is acquired
                 */
                timeout(time: 60, unit: 'MINUTES') {
                    script {
                        stepUnstashRIOT()
                        common.flashAndRiotTestNodes(totalResults)
                    }
                }
            }
        }
    }]}
}
