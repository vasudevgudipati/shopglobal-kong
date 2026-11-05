// ShopGlobal Kong - PROD APPLICATION pipeline (mock API a+b and the website; the agent is not built yet).
// Jenkins script path: jenkins/prod/app.Jenkinsfile        Job name: shopglobal-prod-app
// APPLY unticked = read-only preflight + plan (builds nothing). APPLY ticked = build, push, approval, deploy, verify.
// Mock API: version a (stable, kongdp1:8081) first - it seeds an empty schema - then b (canary, kongdp2:8082). Kong's upstream weights are set in apiops/prod/kong.yaml.
// Website: kongdp1, NodePort 30200, calls Kong in-cluster on the token-free route /internal/api (no Keycloak client). Does NOT touch Konnect.
// Jenkins credentials: dockerhub, shopglobal-db-prod (user SHOPGLOBAL_PROD + password).
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  parameters {
    booleanParam(name: 'APPLY', defaultValue: false, description: 'Tick to deploy to PROD. Left unticked, the job only checks and shows the plan.')
    choice(name: 'SERVICE', choices: ['all', 'mock-api', 'web'], description: 'What to build and deploy')
    string(name: 'AGENT_URL', defaultValue: 'http://localhost:8082', description: 'PROD agent URL for the website chat (set when the agent exists)')
  }
  environment {
    KC       = '/var/lib/jenkins/.kube/config-prod'
    NS       = 'shopglobal-app-prod'
    REPO     = 'vasudevgudipati'
    TAG      = "1.0.${env.BUILD_NUMBER}-prod"
    WEB_PORT = '30200'
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
    stage('Preflight (read-only)') {
      steps {
        sh '''
          set -e
          K="kubectl --kubeconfig $KC"
          echo "--- nodes"; $K get nodes
          for n in kongdp1 kongdp2; do $K get node $n -o jsonpath='{.metadata.name} Ready={.status.conditions[?(@.type=="Ready")].status}{"\\n"}'; done
          echo "--- namespace"; $K get ns $NS
          echo "--- pods and services in $NS"; $K -n $NS get pods,svc -o wide
          echo "--- NodePort $WEB_PORT must be free or already ours"
          owner=$($K get svc -A -o jsonpath='{range .items[*]}{.metadata.namespace}/{.metadata.name}{" "}{.spec.ports[*].nodePort}{"\\n"}{end}' | awk -v p=$WEB_PORT '{for(i=2;i<=NF;i++) if($i==p) print $1}')
          if [ -n "$owner" ] && [ "$owner" != "$NS/shopglobal-web" ]; then echo "NodePort $WEB_PORT is used by $owner"; exit 1; fi
          echo "NodePort $WEB_PORT: ok ${owner:-(free)}"
        '''
      }
    }
    stage('Plan (kubectl diff)') {
      when { expression { !params.APPLY } }
      steps {
        sh '''
          set -e
          img=$REPO/shopglobal-mock-api:$TAG
          if [ "$SERVICE" != web ]; then
            sed -e "s#IMAGE_MOCK_API_[AB]#$img#" -e "s#REPLICAS_MOCK_API_B#1#" k8s/prod/shopglobal-mock-api.yaml > .plan-api.yaml
            echo "--- mock API plan"; kubectl --kubeconfig $KC diff -f .plan-api.yaml || true
          fi
          if [ "$SERVICE" != mock-api ]; then
            sed -e "s#IMAGE_WEB#$REPO/shopglobal-web:$TAG#" -e "s#AGENT_URL#$AGENT_URL#" k8s/prod/shopglobal-web.yaml > .plan-web.yaml
            echo "--- website plan"; kubectl --kubeconfig $KC diff -f .plan-web.yaml || true
          fi
          rm -f .plan-api.yaml .plan-web.yaml
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
    stage('Secrets') {
      when { expression { params.APPLY } }
      steps {
        script {
          sh 'kubectl --kubeconfig $KC create namespace $NS --dry-run=client -o yaml | kubectl --kubeconfig $KC apply -f -'
          if (params.SERVICE != 'web') {
            withCredentials([usernamePassword(credentialsId: 'shopglobal-db-prod', usernameVariable: 'DB_U', passwordVariable: 'DB_P')]) {
              sh '''kubectl --kubeconfig $KC -n $NS create secret generic shopglobal-db --from-literal=DB_USER="$DB_U" --from-literal=DB_PASSWORD="$DB_P" --dry-run=client -o yaml | kubectl --kubeconfig $KC apply -f -'''
            }
          }
        }
      }
    }
    stage('Deploy mock API a, then b') {
      when { expression { params.APPLY && params.SERVICE != 'web' } }
      steps {
        sh '''
          set -e
          K="kubectl --kubeconfig $KC -n $NS"
          cur() { $K get deploy "$1" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true; }
          newimg=$REPO/shopglobal-mock-api:$TAG
          oldB=$(cur shopglobal-mock-api-b)
          repB=$($K get deploy shopglobal-mock-api-b -o jsonpath='{.spec.replicas}' 2>/dev/null || true); [ -n "$repB" ] || repB=0
          # step 1: a gets the new image; b keeps its old image (0 replicas on a first deploy) so one instance always serves
          sed -e "s#IMAGE_MOCK_API_A#$newimg#" -e "s#IMAGE_MOCK_API_B#${oldB:-$newimg}#" -e "s#REPLICAS_MOCK_API_B#$repB#" k8s/prod/shopglobal-mock-api.yaml > .api-rendered.yaml
          kubectl --kubeconfig $KC apply -f .api-rendered.yaml
          $K rollout status deploy/shopglobal-mock-api-a --timeout=600s
          # step 2: b gets the new image and 1 replica
          sed -e "s#IMAGE_MOCK_API_A#$newimg#" -e "s#IMAGE_MOCK_API_B#$newimg#" -e "s#REPLICAS_MOCK_API_B#1#" k8s/prod/shopglobal-mock-api.yaml > .api-rendered.yaml
          kubectl --kubeconfig $KC apply -f .api-rendered.yaml
          $K rollout status deploy/shopglobal-mock-api-b --timeout=600s
          rm -f .api-rendered.yaml
        '''
      }
    }
    stage('Deploy website') {
      when { expression { params.APPLY && params.SERVICE != 'mock-api' } }
      steps {
        sh '''
          set -e
          sed -e "s#IMAGE_WEB#$REPO/shopglobal-web:$TAG#" -e "s#AGENT_URL#$AGENT_URL#" k8s/prod/shopglobal-web.yaml > .web-rendered.yaml
          kubectl --kubeconfig $KC apply -f .web-rendered.yaml
          rm -f .web-rendered.yaml
          kubectl --kubeconfig $KC -n $NS rollout status deploy/shopglobal-web --timeout=600s
        '''
      }
    }
    stage('Verify') {
      when { expression { params.APPLY } }
      steps {
        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
          sh '''
            kubectl --kubeconfig $KC -n $NS get pods,svc -o wide
            if [ "$SERVICE" != mock-api ]; then
              echo "website via NodePort on kongdp1 and kongdp2:"
              for h in 192.168.19.1 192.168.19.2; do curl -s -o /dev/null -m 10 -w "$h HTTP %{http_code}\\n" http://$h:$WEB_PORT/actuator/health/readiness || true; done
            fi
            echo "Kong internal route (from a pod on kongdp1, like the website; 200 = reached, 403 = Kong chat must allow 10.244.1.0/24):"
            kubectl --kubeconfig $KC -n $NS run kongcheck-$BUILD_NUMBER --rm -i --restart=Never --image=curlimages/curl \
              --overrides='{"spec":{"nodeSelector":{"kubernetes.io/hostname":"kongdp1"}}}' --command -- \
              sh -c 'curl -s -o /dev/null -w "HTTP %{http_code}\n" http://kong-api-dp-prod-kong-proxy.shopglobal-prod-api.svc.cluster.local/internal/api/categories' || true
          '''
        }
      }
    }
  }
}
