// ShopGlobal Kong - UAT pipeline. Runs on the Jenkins host (built-in node).
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  environment {
    KUBECONFIG   = '/var/lib/jenkins/.kube/config'
    CP_NAME      = 'shopglobal-uat'
    KONNECT_ADDR = 'https://us.api.konghq.com'
    NS_API       = 'kong-uat-api'
    NS_AI        = 'kong-uat-ai'
    TF_DIR       = 'terraform/envs/uat'
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
              for ns in $NS_API $NS_AI; do
                kubectl create namespace $ns --dry-run=client -o yaml | kubectl apply -f -
                kubectl -n $ns create secret tls kong-cluster-cert --cert=/tmp/dp.crt --key=/tmp/dp.key --dry-run=client -o yaml | kubectl apply -f -
              done
              rm -f /tmp/dp.crt /tmp/dp.key
            '''
          }
        }
      }
    }
    stage('Deploy data planes (Helm)') {
      steps {
        withCredentials([string(credentialsId: 'konnect-pat', variable: 'TF_VAR_konnect_pat')]) {
          sh '''
            set -e
            cd $TF_DIR
            CP=$(terraform output -raw control_plane_endpoint | sed 's#https://##; s#/.*##')
            TP=$(terraform output -raw telemetry_endpoint    | sed 's#https://##; s#/.*##')
            cd - >/dev/null
            helm repo add kong https://charts.konghq.com >/dev/null 2>&1 || true
            helm repo update
            for p in api:$NS_API ai:$NS_AI; do
              n=${p%%:*}; ns=${p##*:}
              helm upgrade --install kong-$n-dp kong/kong -n $ns \
                -f helm/uat/common-dp.yaml -f helm/uat/$n-dp.yaml \
                --set env.cluster_control_plane=$CP:443 --set env.cluster_server_name=$CP \
                --set env.cluster_telemetry_endpoint=$TP:443 --set env.cluster_telemetry_server_name=$TP \
                --set env.cluster_dp_labels="env:uat\\,type:$n" \
                --wait --timeout 5m
            done
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
