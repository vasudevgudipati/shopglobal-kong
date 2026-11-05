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
            kubectl --kubeconfig $KC_AI create namespace $NS --dry-run=client -o yaml | kubectl --kubeconfig $KC_AI apply -f -
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
        // The manifest carries a placeholder image tag (1.0.0-uat). It is replaced here before "kubectl apply": by this build's tag for
        // what was built, and by the image that is running now for what was not built, so a partial run never rolls the other one back.
        withEnv(["BUILD_API=${params.SERVICE in ['all', 'mock-api']}", "BUILD_WEB=${params.SERVICE in ['all', 'web']}"]) {
          sh '''
            set -e
            K="kubectl --kubeconfig $KC_API -n $NS"
            cur() { $K get deploy "$1" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true; }
            api=$(cur shopglobal-mock-api); web=$(cur shopglobal-web)
            [ "$BUILD_API" = true ] && api=$REPO/shopglobal-mock-api:$TAG
            [ "$BUILD_WEB" = true ] && web=$REPO/shopglobal-web:$TAG
            cp k8s/uat/shopglobal-app.yaml .app-rendered.yaml
            [ -n "$api" ] && sed -i "s#image: .*shopglobal-mock-api:.*#image: $api#" .app-rendered.yaml
            [ -n "$web" ] && sed -i "s#image: .*shopglobal-web:.*#image: $web#" .app-rendered.yaml
            kubectl --kubeconfig $KC_API apply -f .app-rendered.yaml
            rm -f .app-rendered.yaml
            [ "$BUILD_API" = true ] && $K rollout status deploy/shopglobal-mock-api --timeout=300s
            [ "$BUILD_WEB" = true ] && $K rollout status deploy/shopglobal-web --timeout=300s
            true
          '''
        }
      }
    }
    stage('Deploy agent (.42)') {
      when { expression { params.SERVICE in ['all', 'agent'] } }
      steps {
        sh '''
          set -e
          sed "s#image: .*shopglobal-agent:.*#image: $REPO/shopglobal-agent:$TAG#" k8s/uat/shopglobal-agent.yaml > .agent-rendered.yaml
          kubectl --kubeconfig $KC_AI apply -f .agent-rendered.yaml
          rm -f .agent-rendered.yaml
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
