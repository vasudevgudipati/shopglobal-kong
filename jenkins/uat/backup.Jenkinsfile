// ShopGlobal Kong - UAT BACKUP pipeline (run before risky changes, or on a schedule).
// Jenkins script path: jenkins/uat/backup.Jenkinsfile
// 1. Dumps the live API gateway config (decK) and the live AI gateway config (kongctl) from Konnect.
// 2. Archives them as Jenkins build artifacts (kept 30 builds). Konnect never returns secret values,
//    so the dumps hold no passwords, but keep the artifacts private anyway.
// Restore: API  -> deck gateway sync api-gateway-dump.yaml ...
//          AI   -> kongctl apply -f ai-gateway-dump.yaml ...
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps(); buildDiscarder(logRotator(numToKeepStr: '30')) }
  environment {
    ENV_NAME     = 'uat'
    CP_NAME      = 'shopglobal-api-uat'
    KONNECT_ADDR = 'https://in.api.konghq.com'
    OUT          = "${WORKSPACE}/backup-out"
  }
  stages {
    stage('Dump API gateway (decK)') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'KONNECT_TOKEN')]) {
          sh '''
            set -e
            mkdir -p "$OUT"
            deck gateway dump --konnect-token "$KONNECT_TOKEN" --konnect-addr $KONNECT_ADDR \
              --konnect-control-plane-name $CP_NAME -o "$OUT/api-gateway-dump.yaml" --with-id=false
            wc -l "$OUT/api-gateway-dump.yaml"
          '''
        }
      }
    }
    stage('Dump AI gateway (kongctl)') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'KONNECT_TOKEN')]) {
          // Does not fail the whole backup if the dump command differs in this kongctl version.
          catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
            sh '''
              set -e
              kongctl dump declarative --resources ai_gateways --include-child-resources \
                --pat "$KONNECT_TOKEN" --region in > "$OUT/ai-gateway-dump.yaml"
              wc -l "$OUT/ai-gateway-dump.yaml"
            '''
          }
        }
      }
    }
    stage('Archive') {
      steps {
        sh '''
          cp -r apiops/$ENV_NAME "$OUT/git-apiops" || true
          cp -r aigw/$ENV_NAME "$OUT/git-aigw" || true
          date -u +%FT%TZ > "$OUT/taken-at.txt"; git rev-parse HEAD >> "$OUT/taken-at.txt" || true
        '''
        archiveArtifacts artifacts: 'backup-out/**', fingerprint: true
      }
    }
  }
}
