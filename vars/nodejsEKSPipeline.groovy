def call(configMap) {
    pipeline {
    agent { 
        node { 
            label 'ROBOSHOP' 
        } 
    }
    environment {
        def appVersion = ""
        ACC_ID = "565139240657"
        project = configMap.get("project")
        component = configMap.get("component")
        repo = "dharma0907"
    }
    options {
        disableConcurrentBuilds()
        timeout(time: 15, unit: 'MINUTES')
    }
    // parameters {
    //     string(name: 'PERSON', defaultValue: 'Mr Jenkins', description: 'Who should I say hello to?')
    //     text(name: 'BIOGRAPHY', defaultValue: '', description: 'Enter some information about the person')
    //     booleanParam(name: 'DEPLOY', defaultValue: true, description: 'Toggle this value')
    //     choice(name: 'CHOICE', choices: ['One', 'Two', 'Three'], description: 'Pick something')
    //     password(name: 'PASSWORD', defaultValue: 'SECRET', description: 'Enter a password')
    // }
    // Build
    stages {
        stage('Read Version') {
            steps {
                     script {
                    def packageJson = readJSON file: 'package.json'
                    // Extract the version property
                    appVersion = packageJson.version
                    echo "The application version is: ${appVersion}"
                }
            }
        }
        stage('Install Dependencies') {
            steps {
                script {
                    sh """
                        npm install

                    """
                }
            }
        }
         stage('Unit Test') {
            steps {
                script {
                    try{

                        sh """
                          npm test

                        """
                        utils.updateCommitStatus("success", "unit tests are successful", "unit-tests")

                    }
                    catch(Exception step){
                         utils.updateCommitStatus("Failed", "unit tests are failed", "unit-tests")

                    }
                   
                }
            }
        }
        // stage('Docker build') {
        //     steps {
        //         script {
        //             sh """
        //                 docker build -t catalogue:${appVersion} .

        //             """
        //         }
        //     }
        // }
          /* stage('SonarQube Analysis') {
            steps {
                // 'My SonarQube Server' must match the name configured in Jenkins System Settings
                withSonarQubeEnv('sonar-server') {
                    sh "${tool 'sonar-8'}/bin/sonar-scanner"
                }
            }
        }
        stage('SonarQube Quality Gate') {
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    script {
                        def qg = waitForQualityGate() // Pauses pipeline
                        if (qg.status != 'OK') {
                            error "Pipeline aborted: ${qg.status}"
                        }
                    }
                }
            }
        } */
        stage('Check Dependabot Alerts') {
            steps {
                script{
                    try{
                        withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
                            sh '''
                                set -e

                                REPO="${repo}/${component}"

                                curl -s -L \
                                -H "Accept: application/vnd.github+json" \
                                -H "Authorization: Bearer ${GH_TOKEN}" \
                                -H "X-GitHub-Api-Version: 2026-03-10" \
                                "https://api.github.com/repos/${REPO}/dependabot/alerts?state=open" \
                                -o alerts.json

                                echo "---- Open Dependabot Alerts ----"
                                jq -r '.[] | "\\(.number)\\t\\(.security_vulnerability.severity)\\t\\(.dependency.package.name)\\t\\(.security_advisory.ghsa_id)"' alerts.json

                                HIGH_CRITICAL_COUNT=$(jq '[.[] | select(.security_vulnerability.severity == "high" or .security_vulnerability.severity == "critical")] | length' alerts.json)

                                echo "High/Critical alert count: ${HIGH_CRITICAL_COUNT}"

                                if [ "$HIGH_CRITICAL_COUNT" -gt 0 ]; then
                                    echo "❌ Found ${HIGH_CRITICAL_COUNT} High/Critical severity dependency alert(s). Failing build."
                                    exit 1
                                else
                                    echo "✅ No High/Critical dependency alerts found."
                                fi
                            '''
                            utils.updateCommitStatus("success", "library scan success", "library-scan")
                        }
                    }
                    catch (Exception e){
                                utils.updateCommitStatus("failure", "library scan failed", "library-scan")
                                throw e
                        }

                }
            }
        }
        stage('Docker BUild and tag') {
            steps {
                
                script {
                        try{

                        // 'aws-global-creds' is the ID of your Jenkins credential entry
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com
                                    docker build -t ${project}/${component}:${appVersion} .
                                    docker tag ${project}/${component}:${appVersion} ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                            
                                """
                                
                            }
                            utils.updateCommitStatus("success", "Build docker success", "build-image")
                        }
                        catch(Exception e){
                                utils.updateCommitStatus("failure", "image build failed", "build-image")
                                throw e
                        } 
                                  
                }        
            }
        }
        stage('Trivy Scan') {
            steps {
                script {
                    def dockerfileScan = sh(
                        script: """
                            trivy config --exit-code 1 --severity HIGH,CRITICAL --format table ./Dockerfile
                        """,
                        returnStatus: true
                    )

                    def imageScan = sh(
                        script: """
                            trivy image --scanners vuln --pkg-types os --exit-code 1 --severity HIGH,CRITICAL --format table ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                        """,
                        returnStatus: true
                    )

                    if (dockerfileScan != 0 || imageScan != 0) {
                        utils.updateCommitStatus("failure", "trivy scan failed", "trivy-scan")
                        error "Trivy found HIGH/CRITICAL issues in Dockerfile and/or OS packages. Failing pipeline."
                    }
                    else{
                        utils.updateCommitStatus("success", "trivy scan success", "trivy-scan")
                    }
                }
            }
        }
        stage('Docker Push to ECR') {
            steps {
                
                script {
                     try{
                            // 'aws-global-creds' is the ID of your Jenkins credential entry
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com
                                docker push  ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                                """
                            }
                            utils.updateCommitStatus("success", "image push success", "push-image")
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus("failure", "image push failed", "push-image")
                            throw e
                        }
             
                }        
            }
        }
        stage('Deploy') {
            steps {
                script {
                    try{
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws eks update-kubeconfig --region us-east-1 --name roboshop-dev
                                    cd helm
                                    helm upgrade --install ${component} -f values-dev.yaml -n roboshop-dev \
                                    --set deployment.imageVersion=${appVersion} \
                                    --wait --timeout 5m .

                                    kubectl rollout status deployment/${component} -n roboshop-dev --timeout=2m
                                """

                            }
                     utils.updateCommitStatus("success", "dev deploy succcess", "dev-deploy")
                    }
                    catch(Exception e){
                        utils.updateCommitStatus("failure", "dev deploy failed", "dev-deploy")
                        throw e
                    }
            
                }
            }

        }
        stage('api-testing'){
            steps{
                script{
                    try{
                      build job: 'ROBOSHOP/catalogue-api-test', 
                      parameters: [
                          string(name: 'NAMESPACE', value: 'roboshop-dev'),
                          string(name: 'COMMIT_ID', value: "${env.GIT_COMMIT}")
                      ],
                      wait: true,
                      propagate: true,
                      quietPeriod: 10

                      utils.updateCommitStatus("success", "api tests success", "api-tests")
                       
                    }
                    catch(Exception e){
                            utils.updateCommitStatus("failure", "api tests failed", "api-tests")
                            throw e
                        }
                    
                }
            }
        }

    }

    post { 
            always { 
                echo 'I will always say Hello again!'
            }
            success {
                slackSend channel: '#jenkins-alerts',
                        color: 'good',
                        message: "SUCCESS: Job '${env.JOB_NAME}' (${env.BUILD_NUMBER}) [ <${env.BUILD_URL}|View Build> ]"
            }
            failure { 
                slackSend channel: '#jenkins-alerts',
                      color: 'danger',
                      message: "FAILED: Job '${env.JOB_NAME}' (${env.BUILD_NUMBER}) [ <${env.BUILD_URL}|View Build> ]"
            }
    }
}

}