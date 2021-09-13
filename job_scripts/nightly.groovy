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
                    stepCheckoutRobotFWTests()
                    stepCheckoutRobotFWFrontend()
                    stepCheckoutRIOT()
                    stepFillBoardTestQueue()
                    stepArchiveMetadata()
                    stepStashRobotFWTests()
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
        stage('compile results') {
            steps {
                stepGenerateBadge()
                stepCompileResults()
            }
        }
    }
}

/* master steps ============================================================= */
def stepCheckoutRobotFWTests() {
    common.helperCheckoutRepo("https://github.com/RIOT-OS/RobotFW-tests.git",
                       "",
                       "refs/heads/master")
}

def stepCheckoutRobotFWFrontend() {
    common.helperCheckoutRepo("https://github.com/RIOT-OS/RobotFW-frontend.git",
                       "",
                       "main",
                       "RobotFW-frontend")
}


def stepCheckoutRIOT() {
    common.helperCheckoutRepo("https://github.com/RIOT-OS/RIOT.git",
                       "",
                       "refs/heads/master",
                       "RIOT")
}


def stepFillBoardTestQueue() {
    nodeBoards = common.getBoardsFromNodes()
    tests = common.getTests()
    totalResults = common.getEmptyResultsFromBoards(nodeBoards)
    boardTestQueue = common.getBoardTestQueue(nodeBoards, tests)
}

def stepArchiveMetadata() {
    common.archiveMetadata()
}

def stepStashRobotFWTests() {
    common.stashRobotFWTests()
}

def stepCompileResults()
{
    common.compileResults(true)
}

def stepGenerateBadge()
{
    def resCount =  common.countResults(totalResults)
    common.setBadge(resCount['pass'], resCount['fail'], resCount['boards'])
}

/* riot_build steps =============================================================== */
def buildOnBuilder(String agentName) {
    node("${agentName}") {
        stage("Building on ${agentName}") {
            stepCheckoutRobotFWTests()
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
    common.buildJobs(boardTestQueue, totalResults)
}

/* test node steps ========================================================== */
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
                        common.flashAndRFTestNodes(totalResults)
                    }
                }
            }
        }
    }]}
}
