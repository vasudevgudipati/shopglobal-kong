// ShopGlobal Kong - UAT pipeline. Runs on the Jenkins host (192.168.19.42).
// API data plane -> k3s on 192.168.19.41 ; AI data plane -> k3s on 192.168.19.42
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  environment {
    KC_AI        = '/var/lib/jenkins/.kube/config'      // cluster on .42
    KC_API       = '/var/lib/jenkins/.kube/config-api'  // cluster on .41
    CP_NAME      = 'shopglobal-uat'
    KONNECT_ADDR = 'https://us.api.konghq.com'
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
    stage('Terraform apply') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
          dir(env.TF_DIR) { sh 'terraform apply -input=false tfplan' }
        }
      }
    }
    stage('Namespaces and cert secret') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
          dir(env.TF_DIR) {
            sh '''
              set -e
              umask 077
              terraform output -raw dp_cert_pem > /tmp/dp.crt
              terraform output -raw dp_key_pem  > /tmp/dp.key
              for pair in "$NS_API:$KC_API" "$NS_AI:$KC_AI"; do
                ns=${pair%%:*}; kc=${pair##*:}
                kubectl --kubeconfig $kc create namespace $ns --dry-run=client -o yaml | kubectl --kubeconfig $kc apply -f -
                kubectl --kubeconfig $kc -n $ns create secret tls kong-cluster-cert --cert=/tmp/dp.crt --key=/tmp/dp.key --dry-run=client -o yaml | kubectl --kubeconfig $kc apply -f -
              done
              rm -f /tmp/dp.crt /tmp/dp.key
            '''
          }
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
    stage('Deploy data planes (Helm)') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
          sh '''
            set -e
            CP=$(cd $TF_DIR && terraform output -raw control_plane_endpoint | sed 's#https://##; s#/.*##')
            TP=$(cd $TF_DIR && terraform output -raw telemetry_endpoint    | sed 's#https://##; s#/.*##')
            deploy() {
              n=$1; ns=$2; kc=$3
              helm --kubeconfig $kc upgrade --install kong-$n-dp $CHART_DIR/kong-$CHART_VER.tgz -n $ns \
                -f helm/uat/common-dp.yaml -f helm/uat/$n-dp.yaml \
                --set env.cluster_control_plane=$CP:443 --set env.cluster_server_name=$CP \
                --set env.cluster_telemetry_endpoint=$TP:443 --set env.cluster_telemetry_server_name=$TP \
                --set env.cluster_dp_labels="env:uat\\,type:$n" \
                --wait --timeout 8m
            }
            deploy api $NS_API $KC_API
            deploy ai  $NS_AI  $KC_AI
          '''
        }
      }
    }
    stage('decK sync') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'KONNECT_TOKEN')]) {
          sh 'deck gateway sync apiops/uat/kong.yaml --konnect-token "$KONNECT_TOKEN" --konnect-addr $KONNECT_ADDR --konnect-control-plane-name $CP_NAME'
        }
      }
    }
  }
}
