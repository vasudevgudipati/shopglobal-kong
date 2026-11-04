// ShopGlobal Kong - UAT AI CONFIG pipeline (run on every AI gateway change).
// Jenkins script path: jenkins/uat/ai-config.Jenkinsfile
// Applies aigw/uat/ai-gateway.yaml to the Konnect AI Gateway "shopglobal-ai-uat" with kongctl:
// model providers, models (semantic router, Gemini, OpenAI, Ollama fallback), policies (prompt guard, prompt decorator,
// semantic cache, token quotas), MCP server (REST API as tools).
// Needs: the AI gateway (platform pipeline) and kongctl on the Jenkins host (/usr/local/bin/kongctl).
// "apply" only creates and updates. Removing something from the YAML does not delete it in Konnect.
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  parameters {
    booleanParam(name: 'DIFF_ONLY', defaultValue: true, description: 'Show what would change, but do not apply (untick to deploy)')
  }
  environment {
    CFG_DIR              = 'aigw/uat'
    KONNECT_REGION       = 'in'
    KONGCTL_NO_TELEMETRY = 'true'
  }
  stages {
    stage('Check kongctl') {
      steps { sh 'command -v kongctl && kongctl version' }
    }
    stage('kongctl diff') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat',       variable: 'KONNECT_TOKEN'),
                         string(credentialsId: 'gemini-api-key',    variable: 'GEMINI_API_KEY'),
                         string(credentialsId: 'openai-api-key',    variable: 'OPENAI_KEY')]) {
          sh '''
            set -e
            export OPENAI_AUTH_HEADER="Bearer $OPENAI_KEY"
            kongctl diff --mode apply -f "$CFG_DIR" --pat "$KONNECT_TOKEN" --region "$KONNECT_REGION"
          '''
        }
      }
    }
    stage('kongctl apply') {
      when { expression { return !params.DIFF_ONLY } }
      steps {
        withCredentials([string(credentialsId: 'konnect-pat',       variable: 'KONNECT_TOKEN'),
                         string(credentialsId: 'gemini-api-key',    variable: 'GEMINI_API_KEY'),
                         string(credentialsId: 'openai-api-key',    variable: 'OPENAI_KEY')]) {
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
