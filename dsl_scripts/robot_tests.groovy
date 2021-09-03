pipelineJob('robot_tests') {
    description("Runs robot (PHiLIP enabled) tests on boards based on parameters.")
    logRotator {
        numToKeep(512)
        artifactNumToKeep(32)
    }
    parameters {
        stringParam('HIL_BOARDS', 'all', 'Space separated list of boards to run tests on, if all then all boards connected will run.')
        stringParam('HIL_TESTS', 'all', 'Space separated list of tests to run, if all then all available tests will run.')
        stringParam('ROBOTFW_OWNER', 'RIOT-OS', 'Owner of the RobotFW-tests github repo.')
        stringParam('ROBOTFW_URL', '', 'Override URL of the RobotFW-tests repo, if blank use ROBOTFW_OWNER/RobotFW-tests.')
        stringParam('ROBOTFW_PR', '', 'PR number of RobotFW-tests, if blank use ROBOTFW_BRANCH.')
        stringParam('ROBOTFW_BRANCH', 'refs/heads/master', 'Branch of RobotFW-tests, can be commit id, branch, or tag.')
        stringParam('RIOT_OWNER', 'RIOT-OS', 'Owner of the RIOT github repo.')
        stringParam('RIOT_URL', '', 'Override URL of the RIOT repo, if blank use RIOT_OWNER/RIOT.')
        stringParam('RIOT_PR', '', 'PR number of RIOT, if blank use RIOT_BRANCH.')
        stringParam('RIOT_BRANCH', 'refs/heads/master', 'Branch of RIOT, can be commit id, branch, or tag.')
        stringParam('EXTRA_MAKE_COMMANDS', '', 'Extra flags to use when building with make, for example USEMODULE+=something, note this is built in docker.')
        booleanParam('GENERATE_HTML', false, 'Generate html in artifacts.')
    }
    definition {
        cpsScm {
            lightweight(true)
            scm {
                git {
                    remote {
                        credentials('github_token')
                        github('inetrg/riot-hil-jenkins-ci')
                    }
                    branch('main')
                }
            }
            scriptPath('job_scripts/robot_tests.groovy')
        }
    }
}
