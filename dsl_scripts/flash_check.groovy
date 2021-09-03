pipelineJob('flash_check') {
    description("Flashes a simple firmware on all the HiL boards and tries to recover boards that cannot flash.")
    logRotator {
        numToKeep(90)
    }
    triggers {
        cron('H 22 * * *')
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
            scriptPath('job_scripts/flash_check.groovy')
        }
    }
}
