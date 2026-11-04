// ShopGlobal Kong - UAT PLATFORM pipeline (run rarely: new environment, chart or image upgrade).
// Jenkins script path: jenkins/uat/platform.Jenkinsfile
// Creates/updates: API control plane + Dev Portal (Terraform), API data plane and Redis (.41), AI cluster DNS, Redis Stack and AI gateway data plane (.42).
// Does NOT touch routes/services - that is the api-config pipeline.
// API gateway (classic control plane, Terraform)  -> k3s on 192.168.19.41
// AI gateway  (Konnect AI Gateway, created in UI) -> k3s on 192.168.19.42
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  environment {
    KC_AI        = '/var/lib/jenkins/.kube/config'      // cluster on .42
    KC_API       = '/var/lib/jenkins/.kube/config-api'  // cluster on .41
    CP_NAME      = 'shopglobal-api-uat'
    KONNECT_ADDR = 'https://in.api.konghq.com'
    NS_API       = 'shopglobal-uat-api'
    NS_AI        = 'shopglobal-uat-ai'
    TF_DIR       = 'terraform/envs/uat'
    CHART_VER    = '3.4.1'
    CHART_DIR    = '/var/lib/jenkins/charts'
  }
  stages {
    stage('Terraform plan') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
          dir(env.TF_DIR) {
            sh 'terraform init -input=false && terraform validate && terraform plan -input=false -out=tfplan'
          }
        }
      }
    }
    stage('Terraform apply (API control plane)') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
          dir(env.TF_DIR) { sh 'terraform apply -input=false tfplan' }
        }
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
    stage('API gateway: namespace, cert, data plane (.41)') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
          sh '''
            set -e
            umask 077
            ( cd $TF_DIR
              terraform output -raw dp_cert_pem > /tmp/dp.crt
              terraform output -raw dp_key_pem  > /tmp/dp.key )
            CP=$(cd $TF_DIR && terraform output -raw control_plane_endpoint | sed 's#https://##; s#/.*##')
            TP=$(cd $TF_DIR && terraform output -raw telemetry_endpoint    | sed 's#https://##; s#/.*##')
            kubectl --kubeconfig $KC_API create namespace $NS_API --dry-run=client -o yaml | kubectl --kubeconfig $KC_API apply -f -
            kubectl --kubeconfig $KC_API -n $NS_API create secret tls kong-cluster-cert --cert=/tmp/dp.crt --key=/tmp/dp.key --dry-run=client -o yaml | kubectl --kubeconfig $KC_API apply -f -
            rm -f /tmp/dp.crt /tmp/dp.key
            helm --kubeconfig $KC_API upgrade --install kong-api-dp $CHART_DIR/kong-$CHART_VER.tgz -n $NS_API \
              -f helm/uat/common-dp.yaml -f helm/uat/api-dp.yaml \
              --set env.cluster_control_plane=$CP:443 --set env.cluster_server_name=$CP \
              --set env.cluster_telemetry_endpoint=$TP:443 --set env.cluster_telemetry_server_name=$TP \
              --set env.cluster_dp_labels="env:uat\\,type:api" \
              --wait --timeout 8m
          '''
        }
      }
    }
    stage('Redis cache (.41)') {
      steps {
        withCredentials([string(credentialsId: 'redis-password', variable: 'REDIS_PW')]) {
          sh '''
            set -e
            kubectl --kubeconfig $KC_API create namespace $NS_API --dry-run=client -o yaml | kubectl --kubeconfig $KC_API apply -f -
            printf %s "$REDIS_PW" | kubectl --kubeconfig $KC_API -n $NS_API create secret generic redis-auth --from-file=password=/dev/stdin --dry-run=client -o yaml | kubectl --kubeconfig $KC_API apply -f -
            kubectl --kubeconfig $KC_API apply -f k8s/uat/redis.yaml
            kubectl --kubeconfig $KC_API -n $NS_API rollout status deployment/redis --timeout=180s
          '''
        }
      }
    }
    stage('AI cluster DNS (.42)') {
      steps {
        // Lets pods on .42 (the AI data plane) resolve the API gateway edge name, so MCP tools can call it.
        sh '''
          set -e
          kubectl --kubeconfig $KC_AI apply -f k8s/uat/coredns-custom.yaml
          kubectl --kubeconfig $KC_AI -n kube-system rollout restart deploy coredns
          kubectl --kubeconfig $KC_AI -n kube-system rollout status deploy coredns --timeout=120s
        '''
      }
    }
    stage('Redis Stack (.42)') {
      steps {
        // Vector-capable Redis for the AI gateway: semantic routing, semantic cache, token quotas.
        withCredentials([string(credentialsId: 'redis-password', variable: 'REDIS_PW')]) {
          sh '''
            set -e
            kubectl --kubeconfig $KC_AI create namespace $NS_AI --dry-run=client -o yaml | kubectl --kubeconfig $KC_AI apply -f -
            printf %s "$REDIS_PW" | kubectl --kubeconfig $KC_AI -n $NS_AI create secret generic redis-auth --from-file=password=/dev/stdin --dry-run=client -o yaml | kubectl --kubeconfig $KC_AI apply -f -
            kubectl --kubeconfig $KC_AI apply -f k8s/uat/redis-stack.yaml
            kubectl --kubeconfig $KC_AI -n $NS_AI rollout status deployment/redis-stack --timeout=300s
          '''
        }
      }
    }
    stage('AI gateway: certificate and data plane (.42)') {
      steps {
        withCredentials([file(credentialsId: 'ai-gw-tls-crt', variable: 'AI_CRT'),
                         file(credentialsId: 'ai-gw-tls-key', variable: 'AI_KEY')]) {
          sh '''
            set -e
            . helm/uat/ai-gw-endpoints.env
            case "$CP_HOST$TP_HOST" in *REPLACE_ME*) echo "Fill helm/uat/ai-gw-endpoints.env first"; exit 1 ;; esac
            kubectl --kubeconfig $KC_AI create namespace $NS_AI --dry-run=client -o yaml | kubectl --kubeconfig $KC_AI apply -f -
            kubectl --kubeconfig $KC_AI -n $NS_AI create secret tls kong-cluster-cert --cert=$AI_CRT --key=$AI_KEY --dry-run=client -o yaml | kubectl --kubeconfig $KC_AI apply -f -
            helm --kubeconfig $KC_AI upgrade --install kong-ai-gw $CHART_DIR/kong-$CHART_VER.tgz -n $NS_AI \
              -f helm/uat/ai-dp.yaml \
              --set env.cluster_control_plane=$CP_HOST:443 --set env.cluster_server_name=$CP_HOST \
              --set env.cluster_telemetry_endpoint=$TP_HOST:443 --set env.cluster_telemetry_server_name=$TP_HOST \
              --wait --timeout 10m
          '''
        }
      }
    }
  }
}
