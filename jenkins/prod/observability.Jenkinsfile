// ShopGlobal Kong - PROD OBSERVABILITY pipeline (shared Grafana + Prometheus).
// Jenkins script path: jenkins/prod/observability.Jenkinsfile
// The existing Prometheus (in the UAT cluster on .42) and Grafana are reused. This job:
//   1. adds the PROD scrape targets (env="prod": API nodes :30100, AI nodes :30101) to the Prometheus config and reloads it,
//   2. pushes the PROD dashboards (grafana/prod/*.json) and the UAT dashboards (now pinned to env="uat"),
//   3. lists the Prometheus targets so you can see that every PROD node is UP.
// Needs: Jenkins credential grafana-token. PROD status endpoints must exist (API: k8s/prod/status-api.yaml, AI: k8s/prod/status-ai.yaml; both created by the PROD platform jobs).
// APPLY is off by default: the job only shows what would change.
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  parameters {
    booleanParam(name: 'APPLY', defaultValue: false, description: 'Tick to change Prometheus and Grafana. Unticked = show the plan only.')
  }
  environment {
    APPLY       = "${params.APPLY}"
    KC_AI       = '/var/lib/jenkins/.kube/config'      // UAT cluster on .42 where Prometheus runs
    GRAFANA_URL = 'http://192.168.16.110:31836'
    PROM_URL    = 'http://192.168.19.42:30091'
  }
  stages {
    stage('Run mode') {
      steps {
        script {
          echo "=============================================================="
          echo " APPLY selected : ${params.APPLY}"
          echo " Mode           : ${params.APPLY ? 'APPLY - Prometheus config and Grafana dashboards will change' : 'PLAN ONLY - nothing will be changed'}"
          echo "=============================================================="
        }
      }
    }
    stage('Preflight: are the PROD metrics reachable?') {
      steps {
        // UNSTABLE (not failed) when a node does not answer, so you still see the plan.
        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
          sh '''
            set -e
            bad=0
            for t in 192.168.19.1:30100 192.168.19.2:30100 192.168.19.3:30101 192.168.18.29:30101; do
              code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' http://$t/metrics || true)
              echo "http://$t/metrics -> HTTP $code"
              [ "$code" = "200" ] || bad=1
            done
            [ $bad = 0 ]
          '''
        }
      }
    }
    stage('Prometheus: show change') {
      steps {
        sh '''
          kubectl --kubeconfig $KC_AI diff -f k8s/uat/prometheus.yaml || true
        '''
      }
    }
    stage('Apply') {
      when { expression { return params.APPLY } }
      stages {
        stage('Prometheus: apply and reload') {
          steps {
            sh '''
              set -e
              kubectl --kubeconfig $KC_AI apply -f k8s/uat/prometheus.yaml
              kubectl --kubeconfig $KC_AI -n monitoring rollout status deployment/prometheus --timeout=180s
              echo "Waiting for the new config to reach the pod (about 60 s) ..."
              sleep 75
              curl -sf -X POST $PROM_URL/-/reload
              echo "Prometheus reloaded"
            '''
          }
        }
        stage('Grafana: dashboards') {
          steps {
            withCredentials([string(credentialsId: 'grafana-token', variable: 'GF_TOKEN')]) {
              sh '''
                set -e
                for f in grafana/prod/kong-api-gateway.json grafana/prod/kong-ai-gateway.json grafana/kong-api-gateway.json grafana/kong-ai-gateway.json; do
                  printf '{"dashboard":' > /tmp/dash.json; cat $f >> /tmp/dash.json
                  printf ',"overwrite":true,"message":"jenkins"}' >> /tmp/dash.json
                  code=$(curl -s -o /tmp/gf.out -w '%{http_code}' -X POST -H "Authorization: Bearer $GF_TOKEN" -H 'Content-Type: application/json' -d @/tmp/dash.json $GRAFANA_URL/api/dashboards/db)
                  echo "$f -> Grafana answered HTTP $code: $(cat /tmp/gf.out)"; rm -f /tmp/gf.out /tmp/dash.json
                  [ "$code" -lt 300 ]
                done
              '''
            }
          }
        }
      }
    }
    stage('Verify: Prometheus targets') {
      steps {
        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
          sh '''
            curl -sf $PROM_URL/api/v1/targets | python3 -c "
import sys,json
bad=0
for t in json.load(sys.stdin)['data']['activeTargets']:
    print(t['labels'].get('env'),t['labels']['job'],t['labels'].get('node',''),t['health'],t['scrapeUrl'])
    if t['labels'].get('env')=='prod' and t['health']!='up': bad=1
sys.exit(bad)"
          '''
        }
      }
    }
  }
}
