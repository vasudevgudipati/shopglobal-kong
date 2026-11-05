// ShopGlobal Kong - UAT PORTAL CONFIG pipeline.
// Jenkins script path: jenkins/uat/portal-config.Jenkinsfile
// Publishes the catalog API to the Dev Portal (signed-in users only) and sets up Keycloak single sign-on.
// Needs the Jenkins credential keycloak-portal-client-secret-uat (secret of the Keycloak client kong-portal-uat). Run after the platform pipeline (creates the portal)
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
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat'), string(credentialsId: 'keycloak-portal-client-secret-uat', variable: 'TF_VAR_oidc_client_secret')]) {
          dir(env.PORTAL_DIR) {
            sh 'terraform init -input=false && terraform validate && terraform plan -input=false -out=tfplan'
          }
        }
      }
    }
    stage('Portal apply') {
      when { expression { return !params.DIFF_ONLY } }
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat'), string(credentialsId: 'keycloak-portal-client-secret-uat', variable: 'TF_VAR_oidc_client_secret')]) {
          dir(env.PORTAL_DIR) { sh 'terraform apply -input=false tfplan' }
        }
      }
    }
  }
}
