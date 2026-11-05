// ShopGlobal Kong - PROD API CONFIG pipeline (run on every API change, after UAT passed).
// Jenkins script path: jenkins/prod/api-config.Jenkinsfile
// 1. Renders apiops/prod/kong.yaml (secrets are filled in from Jenkins credentials, never stored in git).
// 2. Shows the decK diff. With APPLY ticked it also saves a backup of the current PROD configuration first.
// 3. Only with APPLY ticked: syncs services, routes, plugins and consumers to the PROD API control plane with decK.
// 4. Updates the API catalog entry with Terraform and smoke-tests BOTH API nodes.
// Needs the platform pipeline (control plane, Redis, data plane) to have run already.
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  parameters {
    booleanParam(name: 'APPLY', defaultValue: false, description: 'Tick to apply to PROD. Left unticked, the job only shows what would change.')
  }
  environment {
    ENV_NAME     = 'prod'
    CP_NAME      = 'shopglobal-api-prod'
    KONNECT_ADDR = 'https://in.api.konghq.com'
    CATALOG_DIR  = 'terraform/catalog/prod'
    RENDERED     = "${WORKSPACE}/.kong-rendered.yaml"
  }
  stages {
    stage('Run mode') {
      steps {
        script {
          def mode = params.APPLY ? 'APPLY - changes WILL be made to PROD' : 'PLAN ONLY - nothing will be changed'
          echo "=============================================================="
          echo " APPLY selected : ${params.APPLY}"
          echo " Mode           : ${mode}"
          echo " Build          : #${env.BUILD_NUMBER}   Started by: ${currentBuild.getBuildCauses()[0]?.shortDescription}"
          echo "=============================================================="
          currentBuild.displayName = "#${env.BUILD_NUMBER} " + (params.APPLY ? 'APPLY' : 'PLAN')
        }
      }
    }
    stage('Render config (inject secrets)') {
      steps {
        withCredentials([string(credentialsId: 'keycloak-introspect-basic-prod', variable: 'KC_BASIC'),
                         string(credentialsId: 'redis-password-prod', variable: 'REDIS_PW'),
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
    stage('Backup current PROD config') {
      when { expression { return params.APPLY } }
      steps {
        // Rollback copy: the configuration as it is now, before this change. Kept with the build.
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'KONNECT_TOKEN')]) {
          sh '''
            set -e
            mkdir -p backup-out
            deck gateway dump --konnect-token "$KONNECT_TOKEN" --konnect-addr $KONNECT_ADDR --konnect-control-plane-name $CP_NAME -o backup-out/prod-api-before-$BUILD_NUMBER.yaml
            ls -l backup-out
          '''
        }
        archiveArtifacts artifacts: 'backup-out/**', allowEmptyArchive: true
      }
    }
    stage('decK sync') {
      when { expression { return params.APPLY } }
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
      when { expression { return params.APPLY } }
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
          dir(env.CATALOG_DIR) { sh 'terraform apply -input=false tfplan' }
        }
      }
    }
    stage('Smoke test (both API nodes)') {
      when { expression { return params.APPLY } }
      steps {
        // 401 (no token) is the expected answer: the node is up and protected. Each API node is tested on its own.
        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
          sh '''
            fail=0
            for node in 192.168.19.1 192.168.19.2; do
              ok=0
              for i in 1 2 3 4 5 6; do
                code=$(curl -s -o /dev/null -w '%{http_code}' http://$node:30080/api/categories || true)
                echo "$node attempt $i: HTTP $code"
                [ "$code" = "401" ] && { ok=1; break; }
                sleep 10
              done
              [ $ok = 1 ] || fail=1
            done
            exit $fail
          '''
        }
      }
    }
  }
  post {
    always { sh 'rm -f "$RENDERED"' }
  }
}
