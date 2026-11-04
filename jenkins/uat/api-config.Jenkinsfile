// ShopGlobal Kong - UAT API CONFIG pipeline (run on every API change).
// Jenkins script path: jenkins/uat/api-config.Jenkinsfile
// 1. Renders apiops/uat/kong.yaml (secrets are filled in from Jenkins credentials, never stored in git).
// 2. Syncs services, routes, plugins and consumers to the API control plane with decK.
// 3. Updates the API catalog entry (API, OpenAPI spec, docs) with Terraform.
// Needs the control plane (platform pipeline) and Redis (platform pipeline) to exist already.
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
    RENDERED     = "${WORKSPACE}/.kong-rendered.yaml"
  }
  stages {
    stage('Render config (inject secrets)') {
      steps {
        withCredentials([string(credentialsId: 'keycloak-introspect-basic', variable: 'KC_BASIC'),
                         string(credentialsId: 'redis-password', variable: 'REDIS_PW'),
                         string(credentialsId: 'opensearch-basic', variable: 'OS_AUTH')]) {
          sh '''
            set -e
            umask 077
            sed -e "s#__KEYCLOAK_BASIC__#${KC_BASIC}#g" -e "s#__REDIS_PASSWORD__#${REDIS_PW}#g" -e "s#__OPENSEARCH_AUTH__#${OS_AUTH}#g" apiops/$ENV_NAME/kong.yaml > "$RENDERED"
            if grep -q '__[A-Z_]*__' "$RENDERED"; then echo "ERROR: a secret placeholder was not replaced"; rm -f "$RENDERED"; exit 1; fi
            echo "rendered $(wc -l < "$RENDERED") lines"
          '''
        }
      }
    }
    stage('decK diff') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'KONNECT_TOKEN')]) {
          sh 'deck gateway diff "$RENDERED" --konnect-token "$KONNECT_TOKEN" --konnect-addr $KONNECT_ADDR --konnect-control-plane-name $CP_NAME'
        }
      }
    }
    stage('decK sync') {
      when { expression { return !params.DIFF_ONLY } }
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'KONNECT_TOKEN')]) {
          sh 'deck gateway sync "$RENDERED" --konnect-token "$KONNECT_TOKEN" --konnect-addr $KONNECT_ADDR --konnect-control-plane-name $CP_NAME'
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
        // The API now requires a token, so 401 (no token) is the expected answer: the gateway is up and protected.
        // Marks the stage red if the data plane does not answer, but does not fail the whole build.
        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
          sh '''
            for i in 1 2 3 4 5 6; do
              code=$(curl -s -o /dev/null -w '%{http_code}' $API_URL/api/categories || true)
              echo "attempt $i: HTTP $code"
              [ "$code" = "401" ] && exit 0
              sleep 10
            done
            exit 1
          '''
        }
      }
    }
  }
  post {
    always { sh 'rm -f "$RENDERED"' }
  }
}
