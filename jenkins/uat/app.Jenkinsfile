// ShopGlobal Kong - UAT APPLICATION pipeline (website, mock API, shopping agent).
// Jenkins script path: jenkins/uat/app.Jenkinsfile
// Builds the three images from app/, pushes them, and rolls them out:
//   mock API + website -> k3s on 192.168.19.41 (kubeconfig config-api), agent -> k3s on 192.168.19.42 (kubeconfig config).
// A new image tag per build, so the nodes never reuse a cached image. The agent's OAuth secret is created from a Jenkins credential; the database secret is created once by hand.
// Run after: platform, api-config, ai-config (the app needs the gateways). Jenkins credential needed: dockerhub (user + token).
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  parameters {
    choice(name: 'SERVICE', choices: ['all', 'mock-api', 'web', 'agent'], description: 'What to build and deploy')
  }
  environment {
    KC_AI   = '/var/lib/jenkins/.kube/config'       // cluster on .42
    KC_API  = '/var/lib/jenkins/.kube/config-api'   // cluster on .41
    NS      = 'shopglobal-app-uat'
    REPO    = 'vasudevgudipati'
    APP_DIR = 'app'
    TAG     = "1.0.${env.BUILD_NUMBER}-uat"
  }
  stages {
    stage('Secrets') {
      steps {
        // The database secret is created once by hand (it holds the Oracle password). The agent's OAuth secret is created here
        // from the Jenkins credential keycloak-ai-client-secret (the same one the ai-config pipeline uses), so no manual step.
        withCredentials([string(credentialsId: 'keycloak-ai-client-secret', variable: 'AI_SECRET')]) {
          sh '''
            set -e
            kubectl --kubeconfig $KC_API -n $NS get secret shopglobal-db >/dev/null 2>&1 || { echo "MISSING on .41: secret shopglobal-db (create it once, see DEPLOY.md)"; exit 1; }
            printf %s "$AI_SECRET" | kubectl --kubeconfig $KC_AI -n $NS create secret generic shopglobal-agent-oauth \
              --from-file=OAUTH_CLIENT_SECRET=/dev/stdin --dry-run=client -o yaml | kubectl --kubeconfig $KC_AI apply -f -
          '''
        }
      }
    }
    stage('Build and push images') {
      steps {
        script {
          def svcs = params.SERVICE == 'all' ? ['mock-api', 'web', 'agent'] : [params.SERVICE]
          withCredentials([usernamePassword(credentialsId: 'dockerhub', usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS')]) {
            sh 'echo "$DH_PASS" | docker login -u "$DH_USER" --password-stdin'
            svcs.each { s ->
              sh "docker build --build-arg BUILD_ENV=uat -t ${REPO}/shopglobal-${s}:${TAG} ${APP_DIR}/shopglobal-${s}"
              sh "docker push ${REPO}/shopglobal-${s}:${TAG}"
            }
          }
        }
      }
    }
    stage('Deploy mock API + website (.41)') {
      when { expression { params.SERVICE != 'agent' } }
      steps {
        script {
          sh "kubectl --kubeconfig $KC_API apply -f k8s/uat/shopglobal-app.yaml"
          if (params.SERVICE in ['all', 'mock-api']) {
            sh "kubectl --kubeconfig $KC_API -n $NS set image deploy/shopglobal-mock-api app=${REPO}/shopglobal-mock-api:${TAG}"
            sh "kubectl --kubeconfig $KC_API -n $NS rollout status deploy/shopglobal-mock-api --timeout=300s"
          }
          if (params.SERVICE in ['all', 'web']) {
            sh "kubectl --kubeconfig $KC_API -n $NS set image deploy/shopglobal-web app=${REPO}/shopglobal-web:${TAG}"
            sh "kubectl --kubeconfig $KC_API -n $NS rollout status deploy/shopglobal-web --timeout=300s"
          }
        }
      }
    }
    stage('Deploy agent (.42)') {
      when { expression { params.SERVICE in ['all', 'agent'] } }
      steps {
        sh '''
          kubectl --kubeconfig $KC_AI apply -f k8s/uat/shopglobal-agent.yaml
          kubectl --kubeconfig $KC_AI -n $NS set image deploy/shopglobal-agent app=$REPO/shopglobal-agent:$TAG
          kubectl --kubeconfig $KC_AI -n $NS rollout status deploy/shopglobal-agent --timeout=300s
        '''
      }
    }
    stage('Health check') {
      steps {
        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
          sh '''
            for u in http://192.168.19.41:30081 http://192.168.19.41:30090 http://192.168.19.42:30082; do
              code=$(curl -s -o /dev/null -w '%{http_code}' $u/actuator/health/readiness || true)
              echo "$u -> HTTP $code"
            done
          '''
        }
      }
    }
  }
}
