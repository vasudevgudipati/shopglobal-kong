// ShopGlobal Kong - PROD AI PLATFORM pipeline (run rarely: new AI node, chart or image upgrade).
// Jenkins script path: jenkins/prod/ai-platform.Jenkinsfile
// Same PROD cluster as the API gateway (bastion kongcp). The AI gateway runs only on the nodes listed in AI_NODES
// (now: kongdp3; add the second node name later and run again - replicas follow the list, one pod per node).
// Creates/updates: node labels (gateway=ai), namespace shopglobal-prod-ai, secrets, Redis Stack, AI data plane, status service.
// The Konnect AI Gateway "shopglobal-ai-prod" itself is created once in the Konnect UI (Terraform cannot create it yet).
// Run 1 (APPLY unticked): checks only. Run 2 (APPLY ticked): applies.
// Does NOT touch the API gateway or the existing "kong" namespace / release.
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  parameters {
    booleanParam(name: 'APPLY', defaultValue: false, description: 'Tick to apply to PROD. Left unticked, the job only checks nodes, credentials and endpoint settings.')
    string(name: 'AI_NODES', defaultValue: 'kongdp3', description: 'Kubernetes node names that run the AI gateway, space separated. One pod per node. Add the second node here when it has joined the cluster.')
  }
  environment {
    KC        = '/var/lib/jenkins/.kube/config-prod'
    NS_AI     = 'shopglobal-prod-ai'
    RELEASE   = 'kong-ai-gw-prod'
    CHART_VER = '3.4.1'
    CHART_DIR = '/var/lib/jenkins/charts'
    AI_NODES  = "${params.AI_NODES}"   // explicit, so the shell steps always see the value
  }
  stages {
    stage('Run mode') {
      steps {
        script {
          env.FIRST_RUN = (params.AI_NODES == null) ? 'true' : 'false'   // parameters are registered by the first run
          if (env.FIRST_RUN == 'true') {
            echo "First run after a Jenkinsfile change: parameters are registered now. Nothing else will run; use Build with Parameters."
          } else {
            def mode = params.APPLY ? 'APPLY - changes WILL be made to PROD' : 'CHECK ONLY - nothing will be changed'
            echo "=============================================================="
            echo " APPLY selected : ${params.APPLY}"
            echo " Mode           : ${mode}"
            echo " AI nodes       : ${params.AI_NODES}"
            echo "=============================================================="
            currentBuild.displayName = "#${env.BUILD_NUMBER} " + (params.APPLY ? 'APPLY' : 'CHECK') + " " + params.AI_NODES
          }
        }
      }
    }
    stage('Preflight') {
      when { expression { return env.FIRST_RUN != 'true' } }
      steps {
        sh '''
          set -e
          [ -n "$AI_NODES" ] || { echo "ERROR: AI_NODES is empty"; exit 1; }
          for n in $AI_NODES; do
            kubectl --kubeconfig $KC get node $n >/dev/null || { echo "ERROR: node $n not found in the cluster"; exit 1; }
            [ "$(kubectl --kubeconfig $KC get node $n -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" = "True" ] || { echo "ERROR: node $n is not Ready"; exit 1; }
            L=$(kubectl --kubeconfig $KC get node $n -o jsonpath='{.metadata.labels.gateway}')
            [ -z "$L" ] || [ "$L" = "ai" ] || { echo "ERROR: node $n is already labelled gateway=$L (used by another gateway)"; exit 1; }
            echo "node $n: Ready"
          done
          echo "Pods currently on the AI nodes (read only):"
          for n in $AI_NODES; do kubectl --kubeconfig $KC get pods -A -o wide --field-selector spec.nodeName=$n; done
          . helm/prod/ai-gw-endpoints.env
          case "$CP_HOST$TP_HOST" in *REPLACE_ME*) echo "ERROR: fill helm/prod/ai-gw-endpoints.env (hostnames from the Konnect wizard for shopglobal-ai-prod) and commit it"; exit 1 ;; esac
          echo "Konnect endpoints: $CP_HOST / $TP_HOST"
          echo "Nodes to run: $(echo $AI_NODES | wc -w)"
        '''
      }
    }
    stage('Check credentials') {
      when { expression { return env.FIRST_RUN != 'true' } }
      steps {
        withCredentials([string(credentialsId: 'redis-password-prod', variable: 'A'),
                         string(credentialsId: 'openai-api-key', variable: 'B'),
                         string(credentialsId: 'opensearch-basic', variable: 'C'),
                         file(credentialsId: 'ai-gw-tls-crt-prod', variable: 'D'),
                         file(credentialsId: 'ai-gw-tls-key-prod', variable: 'E')]) {
          sh 'echo "All credentials exist: redis-password-prod, openai-api-key, opensearch-basic, ai-gw-tls-crt-prod, ai-gw-tls-key-prod"'
        }
      }
    }
    stage('Apply to PROD (only when APPLY is ticked)') {
      when { expression { return env.FIRST_RUN != 'true' && params.APPLY } }
      stages {
        stage('Label AI nodes') {
          steps {
            sh '''
              set -e
              for n in $AI_NODES; do kubectl --kubeconfig $KC label node $n gateway=ai --overwrite; done
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
        stage('Namespace and secrets') {
          steps {
            withCredentials([string(credentialsId: 'redis-password-prod', variable: 'REDIS_PW'),
                             string(credentialsId: 'openai-api-key', variable: 'OPENAI_KEY'),
                             string(credentialsId: 'opensearch-basic', variable: 'OS_AUTH')]) {
              sh '''
                set -e
                kubectl --kubeconfig $KC create namespace $NS_AI --dry-run=client -o yaml | kubectl --kubeconfig $KC apply -f -
                printf %s "$REDIS_PW"            | kubectl --kubeconfig $KC -n $NS_AI create secret generic redis-auth      --from-file=password=/dev/stdin --dry-run=client -o yaml | kubectl --kubeconfig $KC apply -f -
                printf %s "Bearer $OPENAI_KEY"   | kubectl --kubeconfig $KC -n $NS_AI create secret generic openai-auth     --from-file=header=/dev/stdin   --dry-run=client -o yaml | kubectl --kubeconfig $KC apply -f -
                printf %s "$OS_AUTH"             | kubectl --kubeconfig $KC -n $NS_AI create secret generic opensearch-auth --from-file=header=/dev/stdin   --dry-run=client -o yaml | kubectl --kubeconfig $KC apply -f -
              '''
            }
          }
        }
        stage('Redis Stack (semantic routing, cache, token quotas)') {
          steps {
            sh '''
              set -e
              kubectl --kubeconfig $KC apply -f k8s/prod/redis-stack.yaml
              kubectl --kubeconfig $KC -n $NS_AI rollout status deployment/redis-stack --timeout=300s
            '''
          }
        }
        stage('AI gateway: certificate and data plane') {
          steps {
            withCredentials([file(credentialsId: 'ai-gw-tls-crt-prod', variable: 'AI_CRT'),
                             file(credentialsId: 'ai-gw-tls-key-prod', variable: 'AI_KEY')]) {
              sh '''
                set -e
                . helm/prod/ai-gw-endpoints.env
                N=$(echo $AI_NODES | wc -w)
                if [ "$N" -ge 2 ]; then PDB=true; else PDB=false; fi
                echo "Deploying $N AI gateway pod(s), PodDisruptionBudget=$PDB"
                kubectl --kubeconfig $KC -n $NS_AI create secret tls kong-cluster-cert --cert=$AI_CRT --key=$AI_KEY --dry-run=client -o yaml | kubectl --kubeconfig $KC apply -f -
                helm --kubeconfig $KC upgrade --install $RELEASE $CHART_DIR/kong-$CHART_VER.tgz -n $NS_AI \
                  -f helm/prod/ai-dp.yaml \
                  --set replicaCount=$N --set podDisruptionBudget.enabled=$PDB --set podDisruptionBudget.maxUnavailable=1 \
                  --set env.cluster_control_plane=$CP_HOST:443 --set env.cluster_server_name=$CP_HOST \
                  --set env.cluster_telemetry_endpoint=$TP_HOST:443 --set env.cluster_telemetry_server_name=$TP_HOST \
                  --wait --timeout 10m
              '''
            }
          }
        }
        stage('Metrics endpoint') {
          steps {
            sh 'kubectl --kubeconfig $KC apply -f k8s/prod/status-ai.yaml'
          }
        }
        stage('Verify') {
          steps {
            sh '''
              set -e
              kubectl --kubeconfig $KC -n $NS_AI get pods -o wide
              WANT=$(echo $AI_NODES | wc -w)
              GOT=$(kubectl --kubeconfig $KC -n $NS_AI get pods -l app.kubernetes.io/instance=$RELEASE --field-selector=status.phase=Running -o jsonpath='{range .items[*]}{.spec.nodeName}{"\\n"}{end}' | sort -u | wc -l)
              [ "$GOT" = "$WANT" ] || { echo "ERROR: expected $WANT AI gateway pod(s) on $WANT different node(s), found $GOT"; exit 1; }
              echo "OK: $GOT AI gateway pod(s) running, one per node"
              HA=$(kubectl --kubeconfig $KC -n $NS_AI get deploy -l app.kubernetes.io/instance=$RELEASE -o jsonpath='{.items[0].spec.template.spec.hostAliases[0].ip}')
              if [ "$HA" = "192.168.19.10" ]; then echo "OK: pods resolve shopglobal-api.pronteff.com to the virtual IP"; else echo "WARNING: host alias not applied by the chart - make sure DNS resolves shopglobal-api.pronteff.com from the nodes"; fi
              echo "Reminder: when a new AI node is added, add its IP to the internal route allow list in apiops/prod/kong.yaml and the node address to AI_UPSTREAM in the edge job."
            '''
          }
        }
      }
    }
  }
}
