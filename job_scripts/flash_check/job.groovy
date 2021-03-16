// Note: When creating this with jobdsl and casc the dollar sign variables
// Require some special love, a backslash dollar sign and a backslash newline
// is needed it appears...

nodes = nodesByLabel('HIL')
nodeBoards = []

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
                stepGetBoards()
            }
        }
        stage('node test') {
            steps {
                runParallel items: nodeBoards.collect { "\$\
{it}" }
            }
        }
    }

}

def runParallel(args) {
    parallel args.items.collectEntries { name -> [ "\$\
{name}": {

        node (name) {
            stage("\$\
{name}") {
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
                stepFlashAndTest()
            }
        }
    }
}

def stepFlashAndTest()
{
    exit_code = sh script: "make flash-only test -C /opt/RIOT/tests/shell", returnStatus:true
    if (exit_code != 0) {
        sh script: "sudo reboot"
    }
}

def stepGetBoards() {
    nodeBoards = getBoardsFromNodesEnv()
    sh script: "echo collected boards: ${nodeBoards.join(",")}",
            label: "print boards"
}

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
