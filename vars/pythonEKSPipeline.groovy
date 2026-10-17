def call(Map configMap) {

    pipeline {

        agent {
            label 'AGENT-1'
        }

        environment {
            APP_VERSION = ''
            REGION      = 'us-east-1'
            ACC_ID      = '211125615103'
            PROJECT     = configMap.get('project')
            COMPONENT   = configMap.get('component')
        }

        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
        }

        parameters {
            booleanParam(
                name: 'deploy',
                defaultValue: false,
                description: 'Deploy to DEV'
            )
        }

        stages {

            stage('Read version') {
                steps {
                    script {
                        env.APP_VERSION = readFile(file: 'version').trim()
                        echo "App version: ${env.APP_VERSION}"
                    }
                }
            }

            stage('Install Dependencies') {
                steps {
                    sh '''
                        python3 -m pip install -r requirements.txt
                    '''
                }
            }

            stage('Unit Testing') {
                steps {
                    sh '''
                        echo "Running unit tests"
                    '''
                }
            }
              stage('Docker Build') {
                steps {
                    script {
                        withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                            sh """
                                aws ecr get-login-password --region ${REGION} | docker login --username AWS --password-stdin ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com
                                docker build -t ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion} .
                                docker push ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}
                                #aws ecr wait image-scan-complete --repository-name ${PROJECT}/${COMPONENT} --image-id imageTag=${appVersion} --region ${REGION}
                            """
                        }
                    }
                }
            }
            stage('Trigger Deploy') {
                when {
                    expression {
                        return params.deploy
                    }
                }

                steps {
                    script {
                        build(
                            job: "../${env.COMPONENT}-cd",
                            parameters: [
                                string(
                                    name: 'appVersion',
                                    value: env.APP_VERSION
                                ),
                                string(
                                    name: 'deploy_to',
                                    value: 'dev'
                                )
                            ],
                            propagate: false,
                            wait: false
                        )
                    }
                }
            }
        }

        post {
            always {
                echo 'Pipeline completed'
                deleteDir()
            }

            success {
                echo '✅ Pipeline succeeded'
            }

            failure {
                echo '❌ Pipeline failed'
            }
        }
    }
}