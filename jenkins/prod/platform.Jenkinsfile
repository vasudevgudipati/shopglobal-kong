// ShopGlobal Kong - PROD PLATFORM pipeline, API gateway (run rarely: new environment, chart or image upgrade).
// Jenkins script path: jenkins/prod/platform.Jenkinsfile
// One Kubernetes cluster (bastion kongcp, 192.168.19.4). The API gateway runs only on kongdp1 (.1) and kongdp2 (.2).
// Creates/updates: API control plane shopglobal-api-prod (Terraform), node labels, Redis, API data plane (2 pods, one per node).
// Run 1 (APPLY unticked): checks and shows the Terraform plan only. Run 2 (APPLY ticked): applies it.
// Does NOT touch routes/services (api-config pipeline) and does NOT touch the existing "kong" namespace / release.
// The AI gateway (kongdp3 and kongdp4) is a separate pipeline, added later.
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  parameters {
    booleanParam(name: 'APPLY', defaultValue: false, description: 'Tick to apply to PROD. Left unticked, the job only checks the cluster and shows the Terraform plan.')
  }
  environment {
    KC           = '/var/lib/jenkins/.kube/config-prod'   // kubeconfig of the PROD cluster (admin.conf from kongcp, server https://192.168.19.4:6443)
    CP_NAME      = 'shopglobal-api-prod'
    KONNECT_ADDR = 'https://in.api.konghq.com'
    NS_API       = 'shopglobal-prod-api'
    RELEASE      = 'kong-api-dp-prod'
    API_NODES    = 'kongdp1 kongdp2'
    TF_DIR       = 'terraform/envs/prod'
    CHART_VER    = '3.4.1'
    CHART_DIR    = '/var/lib/jenkins/charts'
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
    stage('Preflight (cluster and nodes)') {
      steps {
        sh '''
          set -e
          kubectl --kubeconfig $KC get nodes -o wide
          for n in $API_NODES; do
            kubectl --kubeconfig $KC get node $n >/dev/null || { echo "ERROR: node $n not found"; exit 1; }
            [ "$(kubectl --kubeconfig $KC get node $n -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" = "True" ] || { echo "ERROR: node $n is not Ready"; exit 1; }
          done
          echo "Existing releases (read only, not changed by this pipeline):"
          helm --kubeconfig $KC list -A
        '''
      }
    }
    stage('Terraform plan') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
          dir(env.TF_DIR) {
            sh 'terraform init -input=false && terraform validate && terraform plan -input=false -out=tfplan'
          }
        }
      }
    }
    stage('Apply to PROD (only when APPLY is ticked)') {
      when { expression { return params.APPLY } }
      stages {
        stage('Terraform apply (API control plane)') {
          steps {
            withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
              dir(env.TF_DIR) { sh 'terraform apply -input=false tfplan' }
            }
          }
        }
        stage('Label API nodes') {
          steps {
            sh '''
              set -e
              for n in $API_NODES; do kubectl --kubeconfig $KC label node $n gateway=api --overwrite; done
              kubectl --kubeconfig $KC get nodes -L gateway
            '''
          }
        }
        stage('Fetch Kong chart') {
          steps {
            sh '''
              set -e
              mkdir -p $CHART_DIR
              if [ ! -f $CHART_DIR/kong-$CHART_VER.tgz ]; then
                helm repo add kong https://charts.konghq.com >/dev/null 2>&1 || true
                helm repo update
                for i in 1 2 3 4 5; do
                  helm pull kong/kong --version $CHART_VER -d $CHART_DIR && break
                  echo "chart download failed (attempt $i), retrying in 20s"; sleep 20
                done
              fi
              ls -l $CHART_DIR/kong-$CHART_VER.tgz
            '''
          }
        }
        stage('Redis cache (shared by both API pods)') {
          steps {
            withCredentials([string(credentialsId: 'redis-password-prod', variable: 'REDIS_PW')]) {
              sh '''
                set -e
                kubectl --kubeconfig $KC create namespace $NS_API --dry-run=client -o yaml | kubectl --kubeconfig $KC apply -f -
                printf %s "$REDIS_PW" | kubectl --kubeconfig $KC -n $NS_API create secret generic redis-auth --from-file=password=/dev/stdin --dry-run=client -o yaml | kubectl --kubeconfig $KC apply -f -
                kubectl --kubeconfig $KC apply -f k8s/prod/redis.yaml
                kubectl --kubeconfig $KC -n $NS_API rollout status deployment/redis --timeout=180s
              '''
            }
          }
        }
        stage('API gateway: certificate and data plane (2 pods)') {
          steps {
            withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
              sh '''
                set -e
                umask 077
                ( cd $TF_DIR
                  terraform output -raw dp_cert_pem > /tmp/dp-prod.crt
                  terraform output -raw dp_key_pem  > /tmp/dp-prod.key )
                CP=$(cd $TF_DIR && terraform output -raw control_plane_endpoint | sed 's#https://##; s#/.*##')
                TP=$(cd $TF_DIR && terraform output -raw telemetry_endpoint    | sed 's#https://##; s#/.*##')
                kubectl --kubeconfig $KC create namespace $NS_API --dry-run=client -o yaml | kubectl --kubeconfig $KC apply -f -
                kubectl --kubeconfig $KC -n $NS_API create secret tls kong-cluster-cert --cert=/tmp/dp-prod.crt --key=/tmp/dp-prod.key --dry-run=client -o yaml | kubectl --kubeconfig $KC apply -f -
                rm -f /tmp/dp-prod.crt /tmp/dp-prod.key
                helm --kubeconfig $KC upgrade --install $RELEASE $CHART_DIR/kong-$CHART_VER.tgz -n $NS_API \
                  -f helm/prod/common-dp.yaml -f helm/prod/api-dp.yaml \
                  --set env.cluster_control_plane=$CP:443 --set env.cluster_server_name=$CP \
                  --set env.cluster_telemetry_endpoint=$TP:443 --set env.cluster_telemetry_server_name=$TP \
                  --set env.cluster_dp_labels="env:prod\\,type:api" \
                  --set env.untrusted_lua_sandbox_requires=cjson.safe --set status.enabled=true --set status.http.enabled=true --set status.http.containerPort=8100 \
                  --wait --timeout 8m
              '''
            }
          }
        }
        stage('Status endpoint (metrics)') {
          steps {
            sh 'kubectl --kubeconfig $KC apply -f k8s/prod/status-api.yaml'
          }
        }
        stage('Verify: 2 pods on 2 different nodes') {
          steps {
            sh '''
              set -e
              kubectl --kubeconfig $KC -n $NS_API get pods -o wide
              NODES=$(kubectl --kubeconfig $KC -n $NS_API get pods -l app.kubernetes.io/instance=$RELEASE -o jsonpath='{range .items[*]}{.spec.nodeName}{"\\n"}{end}' | sort -u | wc -l)
              [ "$NODES" = "2" ] || { echo "ERROR: the API gateway pods are not on 2 different nodes"; exit 1; }
              echo "OK: API gateway pods run on 2 different nodes"
            '''
          }
        }
      }
    }
  }
}
