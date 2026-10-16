def call(Map configMap){
    
    pipeline {
        agent { label 'AGENT-1' }

        environment {
            APP_VERSION = ''
            REGION    = "us-east-1"
            ACC_ID    = "211125615103"
            PROJECT   = configMap.get('project')
            COMPONENT = configMap.get('component')
            REGISTRY  = "${ACC_ID}.dkr.ecr.${REGION}.amazonaws.com"
        }

        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
        }

        parameters {
            booleanParam(name: 'deploy', defaultValue: false, description: 'Trigger CD after build')
        }

        stages {

            stage('Read package.json') {
                steps {
                    script {
                        def packageJson = readJSON file: 'package.json'
                        appVersion = packageJson.version
                        echo "Package version: ${appVersion}"
                    }
                }
            }

            stage('Install Dependencies') {
                steps {
                    sh 'npm install'
                }
            }

            stage('Unit Testing') {
                steps {
                    sh 'echo "unit tests"'
                }
            }

            /*
            stage('Sonar Scan') {
                environment {
                    scannerHome = tool 'sonar-7.2'
                }
                steps {
                    withSonarQubeEnv(installationName: 'sonar-7.2') {
                        sh "${scannerHome}/bin/sonar-scanner"
                    }
                }
            }

            stage('Quality Gate') {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }
            */

            // stage('Check Dependabot Alerts') {
            //     environment {
            //         GITHUB_TOKEN = credentials('github-token')
            //     }
            //     steps {
            //         script {
            //             def response = sh(
            //                 script: '''
            //                     curl -sS -f \
            //                     -H "Accept: application/vnd.github+json" \
            //                     -H "Authorization: Bearer $GITHUB_TOKEN" \
            //                     -H "X-GitHub-Api-Version: 2022-11-28" \
            //                     "https://api.github.com/repos/shivapaturi/catalogue/dependabot/alerts?state=open&severity=high,critical&per_page=100"
            //                 ''',
            //                 returnStdout: true
            //             ).trim()

            //             def alerts = readJSON text: response
            //             if (!(alerts instanceof List)) {
            //                 error "Unexpected response from GitHub API: ${response.take(300)}"
            //             }

            //             if (alerts.size() > 0) {
            //                 alerts.each { a ->
            //                     echo "${a.security_advisory?.severity?.toUpperCase()} - ${a.dependency?.package?.name}: ${a.security_advisory?.summary}"
            //                 }
            //                 error "Found ${alerts.size()} open HIGH/CRITICAL Dependabot alerts. Failing pipeline."
            //             }
            //             echo "No HIGH/CRITICAL Dependabot alerts found."
            //         }
            //     }
            // }

            stage('Docker Build') {
                steps {
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

            // stage('Check Scan Results') {
            //     steps {
            //         script {
            //             withAWS(credentials: 'aws-creds', region: "${REGION}") {

            //                 // Start a scan (ignore error if scan-on-push already scanned it / 24h limit)
            //                 sh """
            //                     aws ecr start-image-scan \
            //                     --repository-name ${PROJECT}/${COMPONENT} \
            //                     --image-id imageTag=${appVersion} \
            //                     --region ${REGION} || true

            //                     aws ecr wait image-scan-complete \
            //                     --repository-name ${PROJECT}/${COMPONENT} \
            //                     --image-id imageTag=${appVersion} \
            //                     --region ${REGION}
            //                 """

            //                 def findings = readJSON text: sh(
            //                     script: """
            //                         aws ecr describe-image-scan-findings \
            //                         --repository-name ${PROJECT}/${COMPONENT} \
            //                         --image-id imageTag=${appVersion} \
            //                         --region ${REGION} \
            //                         --output json
            //                     """,
            //                     returnStdout: true
            //                 ).trim()

            //                 def counts   = findings.imageScanFindings?.findingSeverityCounts ?: [:]
            //                 def critical = (counts.CRITICAL ?: 0) as int
            //                 def high     = (counts.HIGH ?: 0) as int
            //                 echo "ECR scan severity counts: ${counts}"

            //                 if (critical + high > 0) {
            //                     def list = findings.imageScanFindings?.findings ?: []
            //                     list.findAll { it.severity in ['CRITICAL', 'HIGH'] }.each {
            //                         echo "${it.severity} - ${it.name}"
            //                     }
            //                     error "Found ${critical} CRITICAL and ${high} HIGH vulnerabilities in image."
            //                 }
            //                 echo "No HIGH/CRITICAL vulnerabilities found."
            //             }
            //         }
            //     }
            // }

            stage('Trigger Deploy') {
                when{
                    expression { params.deploy }
                }
                steps {
                    script {
                        //build job: 'catalogue-cd',
                        build job: "../${COMPONENT}-cd",
                        parameters: [
                            string(name: 'appVersion', value: "${appVersion}"),
                            string(name: 'deploy_to', value: 'dev')
                        ],
                        propagate: false,  // even SG fails VPC will not be effected
                        wait: false // VPC will not wait for SG pipeline completion
                    }
                }
            }
            
        }

       
        post { 
            always { 
                echo 'I will always say Hello again!'
                deleteDir()
            }
            success { 
                echo 'Hello Success'
            }
            failure { 
                echo 'Hello Failure'
            }
        }
    }
}