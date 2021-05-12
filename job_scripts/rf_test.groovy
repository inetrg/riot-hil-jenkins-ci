/* This file uses both decalritive syntax and scripted.
 * This is because delaritive is simpler and generally preferred but suffer
 * from limited dynamic discovery and implementations.
 *
 * @Note If this Jenkinsfile is touched and merged into master it should also
 * manually be merged into nightly to be executed that night, otherwise it is
 * one night behind.
 */


import jenkins.model.*
@NonCPS
def getNodes(String label) {
    jenkins.model.Jenkins.instance.nodes.collect { thisAgent ->
        if (thisAgent.labelString.contains("${label}")) {
            return thisAgent.name
        }
    }
}


@NonCPS
def mapToList(depmap) {
    def dlist = []
    for (def entry2 in depmap) {
        dlist.add(new java.util.AbstractMap.SimpleImmutableEntry(entry2.key, entry2.value))
    }
    dlist
}

/* globals ================================================================== */
/* Global variables are decalred without `def` as they must be used in both
 * declaritive and scripted mode */
nodes = nodesByLabel('HIL')
collectBuilders = [:]
boardTestQueue = []
nodeBoards = []
nodeTests = []
totalResults = [:]
rfCommitId = ""
rfUrl = ""
riotCommitId = ""
riotUrl = ""

/* pipeline ================================================================= */
pipeline {
    agent { label 'master' }
    stages {
        stage('setup master') {
            steps {
                stepCheckoutRobotFWTests()
                stepCheckoutRobotFWFrontend()
                stepCheckoutRIOT()

                stepGetBoards()
                stepGetTests()
                stepGetSupportedBoardTests()
                stepArchiveMetadata()

                stepStashRobotFWTests()
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
                stepCompileResults()
            }
        }
    }
}

/* master steps ============================================================= */

def _get_repo_url(url, owner, repo) {
    if (url != "") {
        return url
    }
    return"https://github.com/${owner}/${repo}.git"
}

def _checkout_repo(url, pr, branch, dir) {
    if (pr != "") {
        sh 'git config --global user.name "riot-hil-bot"'
        sh 'git config --global user.email "riot-hil-bot@haw-hamburg.de"'
        chk = checkout([
            $class: 'GitSCM',
            branches: [[name: "pr/${pr}"]],
            extensions: [[$class: 'RelativeTargetDirectory',
                          relativeTargetDir: dir],
                         [$class: "PreBuildMerge",
                          options: [mergeTarget: "master",
                                    mergeRemote: "origin"]]],
            userRemoteConfigs: [[url: url,
                                 refspec: "+refs/pull/${pr}/head:refs/remotes/origin/pr/${pr}",
                                 credentialsId: 'github_token']]
        ])

    }
    else {
        chk = checkout([
            $class: 'GitSCM',
            branches: [[name: "${branch}"]],
            extensions: [[$class: 'RelativeTargetDirectory',
                          relativeTargetDir: dir]],
            userRemoteConfigs: [[url: url,
                                 credentialsId: 'github_token']]
        ])
    }
    return chk.GIT_COMMIT
}

def stepCheckoutRobotFWTests() {

    rfUrl = _get_repo_url("${params.ROBOTFW_URL}",
                          "${params.ROBOTFW_OWNER}",
                          "RobotFW-tests")

    rfCommitId = _checkout_repo(rfUrl,
                                "${params.ROBOTFW_PR}",
                                "${params.ROBOTFW_BRANCH}",
                                ".")
}

def stepCheckoutRobotFWFrontend() {
    if (params.GENERATE_HTML) {
        rfCommitId = _checkout_repo("https://github.com/RIOT-OS/RobotFW-frontend.git",
                                    "",
                                    "main",
                                    "RobotFW-frontend")
    }
    else {
        echo "Skipped as html is not being generated"
    }
}

def stepCheckoutRIOT() {
    riotUrl = _get_repo_url("${params.RIOT_URL}",
                            "${params.RIOT_OWNER}",
                            "RIOT")

    riotCommitId = _checkout_repo(riotUrl,
                                  "${params.RIOT_PR}",
                                  "${params.RIOT_BRANCH}",
                                  "RIOT")
}

def stepStashRobotFWTests() {
    stash name: "RobotFWTestsRepo",
          excludes: "RIOT/**, RobotFW-frontend/**"
}

def stepUnstashRobotFWTests() {
    unstash name: "RobotFWTestsRepo"
}

/* Runs a script to compile all tests results in the archive. */
def stepCompileResults()
{
    if (params.GENERATE_HTML) {
        ret = sh script: '''
            HIL_JOB_NAME=$(echo ${JOB_NAME}| cut -d'/' -f 1)
            ARCHIVE_DIR=${JENKINS_HOME}/jobs/${HIL_JOB_NAME}/builds/${BUILD_NUMBER}/archive/build/robot/
            if [ -d $ARCHIVE_DIR ]; then
                ./dist/tools/ci/results_to_xml.sh $ARCHIVE_DIR
                cd RobotFW-frontend
                ./scripts/xsltprocw.sh -c ../config-live.xml -b ${HIL_JOB_NAME} -n ${BUILD_NUMBER} -v /var/jenkins_home/jobs/
            fi
        ''', label: "Compile archived results"
    }
    else {
        ret = sh script: '''
            HIL_JOB_NAME=$(echo ${JOB_NAME}| cut -d'/' -f 1)
            ARCHIVE_DIR=${JENKINS_HOME}/jobs/${HIL_JOB_NAME}/builds/${BUILD_NUMBER}/archive/build/robot/
            if [ -d $ARCHIVE_DIR ]; then
                ./dist/tools/ci/results_to_xml.sh $ARCHIVE_DIR
            fi
        ''', label: "Compile archived results"
    }

}

/* node steps =============================================================== */
/* Cleans and clones both RobotFW-Tests and RIOT based on rfUrl,
 * rfCommitId, riotUrl, and riotCommitId in the node workspace.
 */

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
    // Replace label-string with the label name that you may have
    def nodeList = getNodes("riot_build")
    def builders = []
    for(i=0; i < nodeList.size(); i++) {
        if (nodeList[i] != null && Jenkins.instance.getNode(nodeList[i]).toComputer().isOnline()) {
            builders.push(nodeList[i])
        }
    }

    for(i=0; i < builders.size(); i++) {
        def agentName = nodeList[i]
        // skip the null entries in the nodeList
        println "Preparing task for " + agentName
        collectBuilders["Build on " + agentName] = {
            buildOnBuilder(agentName)
        }
    }
}

/* Gets boards to test based on parameters or dynamically all available
 * boards connected to jenkins nodes.
 *
 * Sets nodeBoards
 */
def stepGetBoards() {
    if (params.HIL_BOARDS == 'all') {
        nodeBoards = getBoardsFromNodesEnv()
    }
    else {
        nodeBoards = params.HIL_BOARDS.tokenize(', ')
        /* TODO: Validate if the boards are connected */
    }
    for (board in nodeBoards) {
        totalResults[board] = [:]
    }
    sh script: "echo collected boards: ${nodeBoards.join(",")}",
            label: "print boards"
}

/* Gets unique boards connected to jenkins nodes. */
def getBoardsFromNodesEnv() {
    script {
        boards = []
        for (int i=0; i < nodes.size(); ++i) {
            node (nodes[i]) {
                boards.push(env.BOARD)
            }
        }
        boards.unique()
        return boards
    }
}

/* Gets test based on parameters or dynamically all available
 * tests in RobotFW-Tests.
 *
 * Sets nodeTests
 */
def stepGetTests() {
    if (params.HIL_TESTS == 'all') {
        nodeTests = getTestsFromDir()
    }
    else {
        nodeTests = params.HIL_TESTS.tokenize(', ')
    }

    sh script: "echo collected tests: ${nodeTests.join(",")}",
            label: "print tests"
}

/* Gets tests in tests directory. */
def getTestsFromDir() {
    script {
        tests = sh returnStdout: true,
                script: """
                    for dir in \$(find tests -maxdepth 1 -mindepth 1 -type d); do
                        [ -d \$dir/tests ] && { echo \$dir ; } || true
                    done
                """, label: "Collecting tests"
        tests = tests.tokenize()
        return tests
    }
}

def stepGetSupportedBoardTests() {
    script {
        for (test in nodeTests) {
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                supported_boards = sh returnStdout: true, script: "make --no-print-directory info-boards-supported -C ${test}", label: "Collecting boards supported for ${test}"
                supported_boards = supported_boards.tokenize()
                for (board in nodeBoards) {
                    if (supported_boards.contains(board)) {
                        totalResults[board][test] = ["support": true]
                        boardTestQueue << ["board": (board), "test": (test)]
                    }
                    else {
                        totalResults[board][test] = ["support": false]
                    }
                }
            }
        }
    }
}

/* Iterates through each board in nodeBoards and test in nodeTests and builds. */
def stepBuildJobs() {
    script {
        while (boardTestQueue.size() > 0) {
            boardtest = boardTestQueue.pop()
            buildJob(boardtest["board"], boardtest["test"])
        }
    }
}

/* Iterates through each board in nodeBoards and test in nodeTests and builds
 * then stashes successful build binaries.
 *
 * For example, if a "board=samr21-xpro" and the test is "tests/periph_gpio",
 * the binaries (.hex, .bin, .elf) will be stashed in
 * "samr21_xpro_tests_periph_gpio".
 *
 * @param board The board to build
 * @param test  The test to build
 */
def buildJob(board, test) {
    totalResults[board][test]['build'] = false
    exit_code = sh script: "RIOT_CI_BUILD=1 DOCKER_MAKE_ARGS=-j BUILD_IN_DOCKER=1 BOARD=${board} make -C ${test} clean all ${params.EXTRA_MAKE_COMMANDS}",
        returnStatus: true,
        label: "Build BOARD=${board} TEST=${test}"
    if (exit_code == 0) {
        /* Must remove all / to get stash to work */
        totalResults[board][test]['build'] = true
        s_name = (board + "_" + test).replace("/", "_")
        stash name: s_name,
                includes: "${test}/bin/${board}/*.elf,${test}/bin/${board}/*.hex,${test}/bin/${board}/*.bin"
    }
}

/* Add metadata file to archive */
def stepArchiveMetadata() {
    sh script: """
            mkdir -p build/robot
            python3 dist/tools/ci/env_parser.py -x -g -e --output=build/robot/metadata.xml
            """
    archiveArtifacts artifacts: "build/robot/metadata.xml"
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
                        stepRunNodeTests()
                    }
                }
            }
        }
    }]}
}

/* Tries to flash and test each test.
 *
 * If a test fails it catches and runs through the next one. Successful tests
 * uploads test artificats.
 *
 * Uses nodeTests.
 */
def stepRunNodeTests()
{
    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE', catchInterruptions: false) {
        stage( "${env.BOARD} setup on  ${env.NODE_NAME}"){
            stepUnstashRobotFWTests()
        }
        for (def test in mapToList(totalResults[env.BOARD])) {
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE',
                        catchInterruptions: false) {
                if (test.value["support"]) {
                    if (test.value['build']) {
                        stage("${test.key}") {
                            stepUnstashBinaries(test.key)
                            /* No need to reset as flashing and the test should manage
                            * this */
                            stepFlash(test.key)
                            stepTest(test.key)
                            stepArchiveTestResults(test.key)
                        }
                    }
                    else {
                        stage("Build failing ${test.key}") {
                            stepArchiveFailedTestResults(test.key)
                            error("Build failure")
                        }
                    }
                }
                else {
                    stage("Skipping ${test.key}") {
                        stepArchiveSkippedTestResults(test.key)
                    }
                }
            }
        }
    }
}

/* Unstashes the binaries from the build server. */
def stepUnstashBinaries(test) {
    unstash name: "${env.BOARD}_${test.replace("/", "_")}"
}

/* Flashes binary to the DUT of the node. */
def stepFlash(test)
{
    sh script: "RIOT_CI_BUILD=1 make -C ${test} flash-only", label: "Flash ${test}"
}

/* Cleans the robot test directory and runs the robot tests. */
def stepTest(test)
{
    def test_name = test.replaceAll('/', '_')
    sh script: "make -C ${test} robot-clean || true",
            label: "Cleaning before ${test} test"
    /* We don't want to stop running other tests since the robot-test is
     * allowed to fail */
    catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE',
            catchInterruptions: false) {
        sh script: "make -C ${test} robot-test",
                label: "Run ${test} test"
        if (params.GENERATE_HTML) {
            sh script: "make -C ${test} robot-html || true",
                    label: "Generate ${test} results"
        }
    }
}

/* Archives the test results. */
def stepArchiveTestResults(test)
{
    def test_name = test.replaceAll('/', '_')
    def base_dir = "build/robot/${env.BOARD}/${test_name}/"
    archiveArtifacts artifacts: "${base_dir}*.xml,${base_dir}*.html,${base_dir}*.html,${base_dir}includes/*.html",
            allowEmptyArchive: true
    junit testResults: "${base_dir}xunit.xml", allowEmptyResults: true
}

def stepArchiveFailedTestResults(test)
{
    def test_name = test.replaceAll('/', '_')
    def dir = "build/robot/${env.BOARD}/${test_name}/xunit.xml"
    writeFile file: dir, text: """<?xml version='1.0' encoding='UTF-8'?>
<testsuite errors="0" failures="1" name="${test_name}" skipped="0" tests="1" time="0.000"><testcase classname="${test_name}.build" name="Build" time="0.000"><failure>Build failed</failure></testcase></testsuite>
"""

    archiveArtifacts artifacts: dir
    junit testResults: dir, allowEmptyResults: true
}


def stepArchiveSkippedTestResults(test)
{
    def test_name = test.replaceAll('/', '_')
    def dir = "build/robot/${env.BOARD}/${test_name}/xunit.xml"
    writeFile file: dir, text: """<?xml version='1.0' encoding='UTF-8'?>
<testsuite errors="0" failures="0" name="${test_name}" skipped="1" tests="1" time="0.000"><testcase classname="${test_name}.build" name="Build" time="0.000"><skipped>Test not supported</skipped></testcase></testsuite>
"""

    archiveArtifacts artifacts: dir
    junit testResults: dir, allowEmptyResults: true
}
