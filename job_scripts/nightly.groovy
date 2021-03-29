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
        // this works too
        // if (thisAagent.labelString == "${label}") {
            return thisAgent.name
        }
    }
}


/* globals ================================================================== */
/* Global variables are decalred without `def` as they must be used in both
 * declaritive and scripted mode */
nodes = nodesByLabel('HIL')
collectBuilders = [:]
nodeBoardQueue = []
nodeBoards = []
nodeTests = []
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
                stepGetRfUrlAndCommit()
                stepGetRiotUrlAndCommit()
                stepPrepareNodeWorkingDir(true)
                stepGetBoards()
                stepGetTests()
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

/* Gets the robot framework url and commit id based on pipeline parameters.
 *
 * Sets rfUrl and rfCommitId.
 */
def stepGetRfUrlAndCommit() {
    (rfUrl, rfCommitId) = getUrlAndCommitFromDefault(params.RF_OWNER, "RobotFW-Tests")
    sh script: "echo rfUrl ${rfUrl}"
    sh script: "echo rfCommitId ${rfCommitId}",
            label: "rfCommitId ${rfCommitId}"
}

/* Gets the riot url and commit id based on pipeline parameters.
 *
 * Sets riotUrl and riotCommitId.
 */
def stepGetRiotUrlAndCommit() {
    (riotUrl, riotCommitId) = getUrlAndCommitFromDefault(params.RIOT_OWNER, "RIOT")
    sh script: "echo riotUrl ${riotUrl}"
    sh script: "echo riotCommitId ${riotCommitId}",
            label: "riotCommitId ${riotCommitId}"
}

/* Gets the SHA1 commit id and url of a github default branch.
 *
 * @param repo_owner    The owner of the repo.
 * @param repo_name     The name of the repo.
 *
 * @return              (url, commit)
 */
def getUrlAndCommitFromDefault(repo_owner, repo_name) {
    def query = "-X POST -d \"{\\\"query\\\": \\\"query "
    query = "${query}{repository(name: \\\\\\\"${repo_name}\\\\\\\","
    query = "${query}owner: \\\\\\\"${repo_owner}\\\\\\\") "
    query = "${query}{defaultBranchRef "
    query = "${query}{ target { oid } }}}\\\"}\" "
    query = "${query}https://api.github.com/graphql"
    def jsonObj = queryGithubApi(query)
    return ["https://github.com/${repo_owner}/${repo_name}",
            jsonObj.defaultBranchRef.target.oid]
}

/* Helper function for adding credentials for the github api call.
 *
 * @param query     The post authentication string for the api call
 *
 * @return  json object with api query result
 */
def queryGithubApi(query) {
    def res = ""
    withCredentials([usernamePassword(credentialsId: 'github_token',
                                      usernameVariable: 'USERNAME',
                                      passwordVariable: 'TOKEN')]) {
        res = sh script: '''
            curl -H "Authorization: token $TOKEN" ''' + query,
        label: "Query github api", returnStdout: true
    }
    def jsonObj = readJSON text: res
    return jsonObj.data.repository
}

/* Runs a script to compile all tests results in the archive. */
def stepCompileResults()
{
    checkout([
        $class: 'GitSCM',
        branches: [[name: "refs/heads/main"]],
        extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'RobotFW-frontend']],
        userRemoteConfigs: [[url: "https://github.com/RIOT-OS/RobotFW-frontend.git", credentialsId: 'github_token']]
    ])
    checkout([
            $class: 'GitSCM',
            branches: [[name: "${rfCommitId}"]],
            extensions: [[$class: 'SubmoduleOption', disableSubmodules: true]],
            userRemoteConfigs: [[url: "${rfUrl}", credentialsId: 'github_token']]
        ])
    /* Some hacks are needed since the master must run the script on the
     * archive but there is not simple way of finding the location of the
     * archive. The best way is to take the env vars and parse them to
     * fit the path. The branch name has some kind of hash at the end so an ls
     * and grep should return whatever the directory name is.
     * There is an assumption that the grep will only find one result
     */
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


def stepTransformArchivedResults()
{
    git branch: 'main', url: 'https://github.com/RIOT-OS/RobotFW-frontend.git'
    sh script: "./scripts/xsltprocw.sh -c ../config-live.xml -u master/1 -v /var/jenkins_home/jobs/RIOT-HIL/branches/master/builds/1/archive/build/robot/"
}

/* node steps =============================================================== */
/* Cleans and clones both RobotFW-Tests and RIOT based on rfUrl,
 * rfCommitId, riotUrl, and riotCommitId in the node workspace.
 */

def buildOnBuilder(String agentName, List boards) {
    node("${agentName}") {
        stage("Building on ${agentName}") {
            stepPrepareNodeWorkingDir(true)
            stepArchiveMetadata()
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

    println "nodeBoards = " + nodeBoards
    println "builders = " + builders
    println "nodeBoards.size() = " + nodeBoards.size()
    println "builders.size() = " + builders.size()
    int col_val = nodeBoards.size() / builders.size()
    if (nodeBoards.size() % builders.size()) {
        col_val++
    }
    println "col_val = " + col_val
    def split_boards = nodeBoards.collate(col_val)
    println "split_boards = " + split_boards

    for(i=0; i < builders.size(); i++) {
        def agentName = nodeList[i]
        def boards = split_boards[i]
        // skip the null entries in the nodeList
        println "Preparing task for " + agentName + " on " + boards + " boards"
        collectBuilders["Build on " + agentName] = {
            buildOnBuilder(agentName, boards)
        }
    }
}

def stepPrepareNodeWorkingDir(include_riot)
{
    checkout([
            $class: 'GitSCM',
            branches: [[name: "${rfCommitId}"]],
            extensions: [[$class: 'SubmoduleOption', disableSubmodules: true]],
            userRemoteConfigs: [[url: "${rfUrl}", credentialsId: 'github_token']]
        ])
    if (include_riot == true) {
        checkout([
            $class: 'GitSCM',
            branches: [[name: "${riotCommitId}"]],
            extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'RIOT']],
            userRemoteConfigs: [[url: "${riotUrl}", credentialsId: 'github_token']]
        ])
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
        nodeBoards = params.HIL_BOARDS.replaceAll("\\s", "").tokenize(',')
        /* TODO: Validate if the boards are connected */
    }
    nodeBoardQueue = nodeBoards.clone()
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
        nodeTests = params.HIL_TESTS.replaceAll("\\s", "").tokenize(',')
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

/* Iterates through each board in nodeBoards and test in nodeTests and builds. */
def stepBuildJobs() {
    script {
        while (nodeBoardQueue.size() > 0) {
            def board = nodeBoardQueue.pop()
            for (int t_idx=0; t_idx < nodeTests.size(); t_idx++) {
                buildJob(board, nodeTests[t_idx])
            }
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
    exit_code = sh script: "RIOT_CI_BUILD=1 DOCKER_MAKE_ARGS=-j BUILD_IN_DOCKER=1 BOARD=${board} make -C ${test} clean all",
        returnStatus: true,
        label: "Build BOARD=${board} TEST=${test}"

    if (exit_code == 0) {
        /* Must remove all / to get stash to work */
        s_name = (board + "_" + test).replace("/", "_")
        stash name: s_name,
                includes: "${test}/bin/${board}/*.elf,${test}/bin/${board}/*.hex,${test}/bin/${board}/*.bin"
        sh script: "echo stashed ${s_name}", label: "Stashed ${s_name}"
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
    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
        stage( "${env.BOARD} setup on  ${env.NODE_NAME}"){
            stepPrepareNodeWorkingDir(false)
        }
        for (int i=0; i < nodeTests.size(); i++) {
            stage("${nodeTests[i]}") {
                def timeout_stop_exc = null
                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE',
                        catchInterruptions: false) {
                    /* TODO: Test to see if the test compiled */
                    stepUnstashBinaries(nodeTests[i])
                    /* No need to reset as flashing and the test should manage
                     * this */
                    stepFlash(nodeTests[i])
                    stepTest(nodeTests[i])
                    stepArchiveTestResults(nodeTests[i])
                }
            }
        }
    }
}

/* Prints the useful env to help understand the test conditions. */
def stepPrintEnv()
{
    sh script: 'dist/tools/ci/print_environment.sh', label: "Print environment"
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
        sh script: "make -C ${test} robot-html || true",
                label: "Generate ${test} results"
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
