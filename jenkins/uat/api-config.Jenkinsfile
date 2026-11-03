// ShopGlobal Kong - UAT API CONFIG pipeline (run on every API change).
// Jenkins script path: jenkins/uat/api-config.Jenkinsfile
// 1. Syncs services, routes and plugins from apiops/uat/kong.yaml to the API control plane with decK.
// 2. Updates the API catalog entry (API, OpenAPI spec, docs) with Terraform.
// Needs the control plane to exist already (platform pipeline).
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  parameters {
    booleanParam(name: 'DIFF_ONLY', defaultValue: false, description: 'Show what would change, but do not apply')
  }
  environment {
    ENV_NAME     = 'uat'
    CP_NAME      = 'shopglobal-api-uat'
    KONNECT_ADDR = 'https://in.api.konghq.com'
    API_URL      = 'http://192.168.19.41:30080'
    CATALOG_DIR  = 'terraform/catalog/uat'
  }
  stages {
    stage('decK diff') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'KONNECT_TOKEN')]) {
          sh 'deck gateway diff apiops/$ENV_NAME/kong.yaml --konnect-token "$KONNECT_TOKEN" --konnect-addr $KONNECT_ADDR --konnect-control-plane-name $CP_NAME'
        }
      }
    }
    stage('decK sync') {
      when { expression { return !params.DIFF_ONLY } }
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'KONNECT_TOKEN')]) {
          sh 'deck gateway sync apiops/$ENV_NAME/kong.yaml --konnect-token "$KONNECT_TOKEN" --konnect-addr $KONNECT_ADDR --konnect-control-plane-name $CP_NAME'
        }
      }
    }
    stage('Catalog plan') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
          dir(env.CATALOG_DIR) {
            sh 'terraform init -input=false && terraform validate && terraform plan -input=false -out=tfplan'
          }
        }
      }
    }
    stage('Catalog apply') {
      when { expression { return !params.DIFF_ONLY } }
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
          dir(env.CATALOG_DIR) { sh 'terraform apply -input=false tfplan' }
        }
      }
    }
    stage('Smoke test') {
      when { expression { return !params.DIFF_ONLY } }
      steps {
        // Marks the stage red if the data plane does not answer, but does not fail the whole build.
        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
          sh '''
            for i in 1 2 3 4 5 6; do
              code=$(curl -s -o /dev/null -w '%{http_code}' $API_URL/api/categories || true)
              echo "attempt $i: HTTP $code"
              [ "$code" = "200" ] && exit 0
              sleep 10
            done
            exit 1
          '''
        }
      }
    }
  }
}
