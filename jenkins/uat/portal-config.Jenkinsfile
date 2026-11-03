// ShopGlobal Kong - UAT PORTAL CONFIG pipeline.
// Jenkins script path: jenkins/uat/portal-config.Jenkinsfile
// Publishes the catalog API to the Dev Portal. Run after the platform pipeline (creates the portal)
// and the api-config pipeline (creates the catalog API).
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  parameters {
    booleanParam(name: 'DIFF_ONLY', defaultValue: false, description: 'Show what would change, but do not apply')
  }
  environment {
    PORTAL_DIR = 'terraform/portal/uat'
  }
  stages {
    stage('Portal plan') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
          dir(env.PORTAL_DIR) {
            sh 'terraform init -input=false && terraform validate && terraform plan -input=false -out=tfplan'
          }
        }
      }
    }
    stage('Portal apply') {
      when { expression { return !params.DIFF_ONLY } }
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
          dir(env.PORTAL_DIR) { sh 'terraform apply -input=false tfplan' }
        }
      }
    }
  }
}
