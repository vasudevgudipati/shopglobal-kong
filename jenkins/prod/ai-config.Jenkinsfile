// ShopGlobal Kong - PROD AI CONFIG pipeline (run on every AI gateway change, after UAT passed).
// Jenkins script path: jenkins/prod/ai-config.Jenkinsfile
// Applies aigw/prod/ai-gateway.yaml to the Konnect AI Gateway "shopglobal-ai-prod" with kongctl:
// model providers, models (semantic router, Gemini, OpenAI), policies, Keycloak auth, MCP server (REST API as tools).
// APPLY unticked: shows the diff only. APPLY ticked: applies it.
// Needs the AI platform pipeline (data plane, Redis Stack) and kongctl on the Jenkins host (/usr/local/bin/kongctl).
// "apply" only creates and updates. Removing something from the YAML does not delete it in Konnect.
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  parameters {
    booleanParam(name: 'APPLY', defaultValue: false, description: 'Tick to apply to PROD. Left unticked, the job only shows what would change.')
  }
  environment {
    CFG_DIR              = 'aigw/prod'
    KONNECT_REGION       = 'in'
    KONGCTL_NO_TELEMETRY = 'true'
  }
  stages {
    stage('Run mode') {
      steps {
        script {
          def mode = params.APPLY ? 'APPLY - changes WILL be made to PROD' : 'DIFF ONLY - nothing will be changed'
          echo "=============================================================="
          echo " APPLY selected : ${params.APPLY}"
          echo " Mode           : ${mode}"
          echo "=============================================================="
          currentBuild.displayName = "#${env.BUILD_NUMBER} " + (params.APPLY ? 'APPLY' : 'DIFF')
        }
      }
    }
    stage('Check kongctl') {
      steps { sh 'command -v kongctl && kongctl version' }
    }
    stage('kongctl diff') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat',                  variable: 'KONNECT_TOKEN'),
                         string(credentialsId: 'gemini-api-key',               variable: 'GEMINI_API_KEY'),
                         string(credentialsId: 'openai-api-key',               variable: 'OPENAI_KEY'),
                         string(credentialsId: 'keycloak-ai-client-secret-prod', variable: 'KEYCLOAK_AI_CLIENT_SECRET')]) {
          sh '''
            set -e
            export OPENAI_AUTH_HEADER="Bearer $OPENAI_KEY"
            kongctl diff --mode apply -f "$CFG_DIR" --pat "$KONNECT_TOKEN" --region "$KONNECT_REGION"
          '''
        }
      }
    }
    stage('kongctl apply (only when APPLY is ticked)') {
      when { expression { return params.APPLY } }
      steps {
        withCredentials([string(credentialsId: 'konnect-pat',                  variable: 'KONNECT_TOKEN'),
                         string(credentialsId: 'gemini-api-key',               variable: 'GEMINI_API_KEY'),
                         string(credentialsId: 'openai-api-key',               variable: 'OPENAI_KEY'),
                         string(credentialsId: 'keycloak-ai-client-secret-prod', variable: 'KEYCLOAK_AI_CLIENT_SECRET')]) {
          sh '''
            set -e
            export OPENAI_AUTH_HEADER="Bearer $OPENAI_KEY"
            kongctl apply -f "$CFG_DIR" --auto-approve --write-secrets --pat "$KONNECT_TOKEN" --region "$KONNECT_REGION"
          '''
        }
      }
    }
  }
}
