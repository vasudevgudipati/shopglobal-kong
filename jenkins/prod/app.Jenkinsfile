// ShopGlobal Kong - PROD APPLICATION pipeline (mock API x2 + website). The shopping agent is deployed separately, on its own server.
// Jenkins script path: jenkins/prod/app.Jenkinsfile
// APPLY unticked = plan only (builds nothing, shows what would change). APPLY ticked = build, push, wait for approval, deploy.
// Order matters: mock API A first (it seeds an empty schema), then B, then the website. Run api-config (Kong upstream) BEFORE the website is used.
// Jenkins credentials: dockerhub (user + token), shopglobal-db-prod (username SHOPGLOBAL_PROD + its password).
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  parameters {
    booleanParam(name: 'APPLY', defaultValue: false, description: 'Tick to deploy to PROD. Left unticked, the job only shows what would change.')
    choice(name: 'SERVICE', choices: ['all', 'mock-api', 'web'], description: 'What to build and deploy')
    string(name: 'AGENT_URL', defaultValue: 'http://localhost:8082', description: 'PROD agent URL used by the website chat (set when the agent server is ready)')
  }
  environment {
    KC   = '/var/lib/jenkins/.kube/config-prod'
    NS   = 'shopglobal-app-prod'
    REPO = 'vasudevgudipati'
    TAG  = "1.0.${env.BUILD_NUMBER}-prod"
  }
  stages {
    stage('Run mode') {
      steps {
        script {
          def mode = params.APPLY ? 'APPLY - changes WILL be made to PROD' : 'PLAN ONLY - nothing will be changed'
          echo "=============================================================="
          echo " APPLY selected : ${params.APPLY}"
          echo " Service        : ${params.SERVICE}"
          echo " Mode           : ${mode}"
          echo " Build          : #${env.BUILD_NUMBER}   Started by: ${currentBuild.getBuildCauses()[0]?.shortDescription}"
          echo "=============================================================="
          currentBuild.displayName = "#${env.BUILD_NUMBER} " + (params.APPLY ? 'APPLY' : 'PLAN')
        }
      }
    }
    stage('Cluster check') {
      steps {
        sh '''
          set -e
          kubectl --kubeconfig $KC get nodes
          for n in kongdp1 kongdp2; do kubectl --kubeconfig $KC get node $n >/dev/null || { echo "node $n not found"; exit 1; }; done
        '''
      }
    }
    stage('Plan (server-side dry run)') {
      when { expression { !params.APPLY } }
      steps {
        sh '''
          set -e
          sed -e "s#IMAGE_MOCK_API_[AB]#$REPO/shopglobal-mock-api:$TAG#" -e "s#IMAGE_WEB#$REPO/shopglobal-web:$TAG#" \
              -e "s#REPLICAS_MOCK_API_B#1#" -e "s#AGENT_URL#$AGENT_URL#" k8s/prod/shopglobal-app.yaml > .plan.yaml
          kubectl --kubeconfig $KC diff -f .plan.yaml || true      # exit 1 only means "there are differences"
          rm -f .plan.yaml
          echo "PLAN ONLY - nothing was built or changed. Tick APPLY to deploy."
        '''
      }
    }
    stage('Build and push images') {
      when { expression { params.APPLY } }
      steps {
        script {
          def svcs = params.SERVICE == 'all' ? ['mock-api', 'web'] : [params.SERVICE]
          withCredentials([usernamePassword(credentialsId: 'dockerhub', usernameVariable: 'DH_USER', passwordVariable: 'DH_PASS')]) {
            sh 'echo "$DH_PASS" | docker login -u "$DH_USER" --password-stdin'
            svcs.each { s ->
              sh "docker build --build-arg BUILD_ENV=prod -t ${REPO}/shopglobal-${s}:${TAG} app/shopglobal-${s}"
              sh "docker push ${REPO}/shopglobal-${s}:${TAG}"
            }
          }
        }
      }
    }
    stage('Approve PROD deploy') {
      when { expression { params.APPLY } }
      steps { input message: "Deploy ${TAG} (${params.SERVICE}) to PROD?", ok: 'Deploy' }
    }
    stage('Database secret') {
      when { expression { params.APPLY } }
      steps {
        withCredentials([usernamePassword(credentialsId: 'shopglobal-db-prod', usernameVariable: 'DB_U', passwordVariable: 'DB_P')]) {
          sh '''
            set -e
            kubectl --kubeconfig $KC create namespace $NS --dry-run=client -o yaml | kubectl --kubeconfig $KC apply -f -
            kubectl --kubeconfig $KC -n $NS create secret generic shopglobal-db \
              --from-literal=DB_USER="$DB_U" --from-literal=DB_PASSWORD="$DB_P" --dry-run=client -o yaml | kubectl --kubeconfig $KC apply -f -
          '''
        }
      }
    }
    stage('Deploy mock API A, then B') {
      when { expression { params.APPLY && params.SERVICE != 'web' } }
      steps {
        sh '''
          set -e
          K="kubectl --kubeconfig $KC -n $NS"
          cur() { $K get deploy "$1" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true; }
          web=$(cur shopglobal-web); [ -n "$web" ] || web=$REPO/shopglobal-web:placeholder
          oldB=$(cur shopglobal-mock-api-b); newimg=$REPO/shopglobal-mock-api:$TAG
          repB=$($K get deploy shopglobal-mock-api-b -o jsonpath='{.spec.replicas}' 2>/dev/null || true); [ -n "$repB" ] || repB=0
          # step 1: A gets the new image; B keeps its old image (and stays at 0 replicas on a first deploy) so one instance always serves
          sed -e "s#IMAGE_MOCK_API_A#$newimg#" -e "s#IMAGE_MOCK_API_B#${oldB:-$newimg}#" -e "s#IMAGE_WEB#$web#" \
              -e "s#REPLICAS_MOCK_API_B#$repB#" -e "s#AGENT_URL#$AGENT_URL#" k8s/prod/shopglobal-app.yaml > .app-rendered.yaml
          kubectl --kubeconfig $KC apply -f .app-rendered.yaml
          $K rollout status deploy/shopglobal-mock-api-a --timeout=400s
          # step 2: B gets the new image and 1 replica
          sed -e "s#IMAGE_MOCK_API_A#$newimg#" -e "s#IMAGE_MOCK_API_B#$newimg#" -e "s#IMAGE_WEB#$web#" \
              -e "s#REPLICAS_MOCK_API_B#1#" -e "s#AGENT_URL#$AGENT_URL#" k8s/prod/shopglobal-app.yaml > .app-rendered.yaml
          kubectl --kubeconfig $KC apply -f .app-rendered.yaml
          $K rollout status deploy/shopglobal-mock-api-b --timeout=400s
          rm -f .app-rendered.yaml
        '''
      }
    }
    stage('Deploy website') {
      when { expression { params.APPLY && params.SERVICE != 'mock-api' } }
      steps {
        sh '''
          set -e
          K="kubectl --kubeconfig $KC -n $NS"
          cur() { $K get deploy "$1" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true; }
          a=$(cur shopglobal-mock-api-a); b=$(cur shopglobal-mock-api-b)
          [ -n "$a" ] && [ -n "$b" ] || { echo "Deploy the mock API first (SERVICE=all or mock-api)"; exit 1; }
          sed -e "s#IMAGE_MOCK_API_A#$a#" -e "s#IMAGE_MOCK_API_B#$b#" -e "s#IMAGE_WEB#$REPO/shopglobal-web:$TAG#" \
              -e "s#REPLICAS_MOCK_API_B#1#" -e "s#AGENT_URL#$AGENT_URL#" k8s/prod/shopglobal-app.yaml > .app-rendered.yaml
          kubectl --kubeconfig $KC apply -f .app-rendered.yaml
          rm -f .app-rendered.yaml
          $K rollout status deploy/shopglobal-web --timeout=300s
        '''
      }
    }
    stage('Health check') {
      when { expression { params.APPLY } }
      steps {
        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
          sh '''
            echo "website (NodePort on kongdp1):"; curl -s -o /dev/null -w 'HTTP %{http_code}\\n' http://192.168.19.1:30090/actuator/health/readiness || true
            echo "Kong -> upstream -> mock API (from a pod inside the cluster, internal route):"
            kubectl --kubeconfig $KC -n $NS run kongcheck-$BUILD_NUMBER --rm -i --restart=Never --image=curlimages/curl --command -- \
              sh -c 'for i in 1 2 3 4; do curl -s -o /dev/null -w "HTTP %{http_code}\\n" http://kong-api-dp-prod-kong-proxy.shopglobal-prod-api.svc.cluster.local/internal/api/categories; done' || true
            kubectl --kubeconfig $KC -n $NS get pods -o wide
          '''
        }
      }
    }
  }
}
