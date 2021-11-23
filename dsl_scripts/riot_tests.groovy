pipelineJob('riot_tests') {
    description("Runs riot (on-target) tests on boards based on parameters.")
    logRotator {
        numToKeep(512)
        artifactNumToKeep(512)
    }
    parameters {
        stringParam('HIL_BOARDS', 'all', 'Space separated list of boards to run tests on, if all then all boards connected will run.')
        stringParam('HIL_TESTS', 'tests/shell', 'Space separated list of tests to run.')
        stringParam('RIOT_OWNER', 'RIOT-OS', 'Owner of the RIOT github repo.')
        stringParam('RIOT_URL', '', 'Override URL of the RIOT repo, if blank use RIOT_OWNER/RIOT.')
        stringParam('RIOT_PR', '', 'PR number of RIOT, if blank use RIOT_BRANCH.')
        stringParam('RIOT_BRANCH', 'refs/heads/master', 'Branch of RIOT, can be commit id, branch, or tag.')
        stringParam('EXTRA_MAKE_COMMANDS', '', 'Extra flags to use when building with make, for example USEMODULE+=something, note this is built in docker.')
        stringParam('EXTRA_TEST_COMMANDS', '', 'Extra flags to use when testing with make, for example RIOT_TERMINAL+=something.')
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
            scriptPath('job_scripts/riot_tests.groovy')
        }
    }
}
