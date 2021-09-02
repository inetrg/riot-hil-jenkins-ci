pipeline {
    agent any

    stages {
        stage('Build') {
            steps {
                stepBuildJobs()

            }
        }
    }
}

def stepBuildJobs() {
    script {

        node ("master") {
            checkout([
                $class: 'GitSCM',
                branches: [[name: "refs/heads/pr/jobscripts"]],
                userRemoteConfigs: [[url: "https://github.com/inetrg/riot-hil-jenkins-ci.git",
                                    credentialsId: 'github_token']]
            ])
            step([
                $class: 'ExecuteDslScripts',
                targets: ['dsl_scripts/*.groovy'].join('\n'),
                lookupStrategy: 'SEED_JOB'
            ])
        }
    }
}
