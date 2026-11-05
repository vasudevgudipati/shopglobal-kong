// ShopGlobal Kong - PROD PORTAL CONFIG pipeline.
// Jenkins script path: jenkins/prod/portal-config.Jenkinsfile
// Publishes the PROD catalog API to the PROD Dev Portal (signed-in users only) and sets up Keycloak single sign-on.
// Run after the PROD platform pipeline (creates the portal) and the PROD api-config pipeline (creates the catalog API).
// Needs the Jenkins credentials konnect-pat and keycloak-portal-client-secret-prod (secret of the Keycloak client kong-portal-prod).
// APPLY is off by default: the job only shows the Terraform plan.
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  parameters {
    booleanParam(name: 'APPLY', defaultValue: false, description: 'Tick to change the PROD portal. Unticked = show the plan only.')
  }
  environment {
    PORTAL_DIR = 'terraform/portal/prod'
    APPLY      = "${params.APPLY}"
  }
  stages {
    stage('Run mode') {
      steps {
        script {
          echo "=============================================================="
          echo " APPLY selected : ${params.APPLY}"
          echo " Mode           : ${params.APPLY ? 'APPLY - the PROD portal WILL be changed' : 'PLAN ONLY - nothing will be changed'}"
          echo "=============================================================="
        }
      }
    }
    stage('Adopt existing sign-in') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat'), string(credentialsId: 'keycloak-portal-client-secret-prod', variable: 'TF_VAR_oidc_client_secret')]) {
          dir(env.PORTAL_DIR) {
            sh 'bash ../adopt-existing.sh /var/lib/jenkins/tfstate/shopglobal/prod/terraform.tfstate'
          }
        }
      }
    }
    stage('Portal plan') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat'), string(credentialsId: 'keycloak-portal-client-secret-prod', variable: 'TF_VAR_oidc_client_secret')]) {
          dir(env.PORTAL_DIR) {
            sh 'terraform init -input=false && terraform validate && terraform plan -input=false -out=tfplan'
          }
        }
      }
    }
    stage('Portal apply') {
      when { expression { return params.APPLY } }
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat'), string(credentialsId: 'keycloak-portal-client-secret-prod', variable: 'TF_VAR_oidc_client_secret')]) {
          dir(env.PORTAL_DIR) { sh 'terraform apply -input=false tfplan' }
        }
      }
    }
  }
}
