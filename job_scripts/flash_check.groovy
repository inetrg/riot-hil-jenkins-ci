nodes = nodesByLabel('HIL')

pipeline {
    agent { label 'master' }
    options {
        // If the whole process takes more than x hours then exit
        // This must be longer since multiple jobs can be started but waiting on nodes to complete
        timeout(time: 3, unit: 'HOURS')
        // Failing fast allows the nodes to be interrupted as some steps can take a while
        parallelsAlwaysFailFast()
    }
  stages {
        stage('setup build server and build') {
            steps {
                stepBuildJobs()
            }
        }
        stage('node test') {
            steps {
                runParallel items: nodes.collect { "${it}" }
            }
        }
    }

}

/* builder steps =============================================================== */
def stepBuildJobs() {
    script {
        for (i = 0; i < nodes.size(); i++) {
            board = ""
            node (nodes[i]) {
                board = env.BOARD
            }
            node ('riot_build') {
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "refs/heads/master"]],
                    userRemoteConfigs: [[url: "https://github.com/RIOT-OS/RIOT.git",
                                        credentialsId: 'github_token']]
                ])
                buildJob(board, "tests/shell")
            }
        }
    }
}

def buildJob(board, test) {
    exit_code = sh script: "RIOT_CI_BUILD=1 DOCKER_MAKE_ARGS=-j BUILD_IN_DOCKER=1 BOARD=${board} make -C ${test} all",
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

/* pi steps =============================================================== */
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

def stepRunNodeTests()
{
    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {

        stage("Flash and test") {
            def timeout_stop_exc = null
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE',
                    catchInterruptions: false) {
                stepUnstashBinaries("tests/shell")
                stepFlashAndTest()
            }
        }
    }
}

def stepUnstashBinaries(test) {
    unstash name: "${env.BOARD}_${test.replace("/", "_")}"
    sh script: "rsync -a tests/shell/ /opt/RIOT/tests/shell/"
}

def stepFlashAndTest()
{
    exit_code = sh script: "make flash-only test -C /opt/RIOT/tests/shell", returnStatus:true
    if (exit_code != 0) {
        sh script: "sudo reboot"
    }
}
