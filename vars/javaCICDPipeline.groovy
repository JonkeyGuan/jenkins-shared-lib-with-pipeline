def call(Map params) {

  def appName    = params.appName
  def project    = params.project
  def gitUrl     = params.gitUrl
  def exRegistry = params.targetRegistry
  def artifact   = params.artifact
  def settings   = params.settings ?: "nexus_openshift_settings.xml"
  def sonarUrl   = params.sonarUrl ?: "http://sonarqube-cicd-tools.apps.ocp1.rhcnsa.com/"

  def inRegistry = "image-registry.openshift-image-registry.svc.cluster.local:5000"
  def tag        = "0.0-0"
  def destCreds  = "jonkey+robot:OYNB9Z8QCWQAD26RD2QM9F7PPVZRDZGSPAP2HAG088BQNZDJYS0MX0CD23FZXSQ4"
  def mvnCmd     = "mvn -s ${settings}"

  pipeline {
    agent {
      label "slave"
    }
  
    stages {
      stage('Checkout Source') {
        steps {
          git credentialsId: 'gitea', url: "${gitUrl}"
          script {
            tag  = currentBuild.number
          }
        }
      }
  
      stage('Build Jar File') {
        steps {
          echo "Building version ${tag}"
          sh "${mvnCmd} clean package -DskipTests=true"
        }
      }
  
      stage('Unit Tests') {
        steps {
          echo "Running Unit Tests"
          sh "${mvnCmd} test"
          step([$class: 'JUnitResultArchiver', testResults: '**/target/surefire-reports/TEST-*.xml'])
        }
      }
  
      stage('Code Analysis') {
        steps {
          echo "Running Code Analysis"
          sh "${mvnCmd} sonar:sonar -Dsonar.host.url=${sonarUrl} -Dsonar.projectName=${JOB_BASE_NAME} -Dsonar.projectVersion=${tag}"
        }
      }
  
      stage('Build and Tag OpenShift Image') {
        steps {
            echo "Building OpenShift container image ${appName}:${tag}"
            script {
              openshift.withCluster() {
                openshift.withProject("${project}") {
                  openshift.selector("bc", "${appName}").startBuild("--from-file=${artifact}", "--wait=true")
                  openshift.tag("${appName}:latest", "${appName}:${tag}")
                }
              }
            }
        }
      }
  
      stage('Copy Image to Container Registry') {  
        steps {  
          echo "Copy image to Container Registry"  
          script {  
            sh "skopeo copy --src-tls-verify=false --dest-tls-verify=false --src-creds admin:\$(oc whoami -t) --dest-creds ${destCreds}   docker://${inRegistry}/${project}/${appName}:${tag} docker://${exRegistry}/${appName}:${tag}"
          }
        }
      }
  
      stage('Deploy to Environment') {
        steps {
          echo "Deploying container image to environment"
          script {
            openshift.withCluster() {
              openshift.withProject("${project}") {
                openshift.set("image", "dc/${appName}", "${appName}=${inRegistry}/${project}/${appName}:${tag}")
                openshift.selector("dc", "${appName}").rollout().latest();
  
                sleep 5
                def dc = openshift.selector("dc", "${appName}").object()
                def dcVersion = dc.status.latestVersion
                def rc = openshift.selector("rc", "${appName}-${dcVersion}").object()
  
                echo "Waiting for ReplicationController \"${appName}-${dcVersion}\" to be ready"
                while (rc.spec.replicas != rc.status.readyReplicas) {
                  sleep 5
                  rc = openshift.selector("rc", "${appName}-${dcVersion}").object()
                }
              }
            }
          }
        }
      }
  
    }
  }

}