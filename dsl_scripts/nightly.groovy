pipelineJob('nightly') {
    description("Runs all robot tests on all boards every night and provides latest results and badges.")
    logRotator {
        numToKeep(512)
        artifactNumToKeep(32)
    }
    triggers {
        cron('H 23 * * *')
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
                    branch('pr/jobscripts')
                }
            }
            scriptPath('job_scripts/nightly.groovy')
        }
    }
}
