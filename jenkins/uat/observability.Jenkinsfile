// ShopGlobal Kong - UAT OBSERVABILITY pipeline (Grafana).
// Jenkins script path: jenkins/uat/observability.Jenkinsfile
// Creates/updates the Prometheus data source and the "ShopGlobal Kong" dashboard in Grafana through its API.
// Needs: Prometheus running (platform pipeline) and the Jenkins credential grafana-token (Grafana service-account token, role Editor or Admin).
pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  environment {
    GRAFANA_URL = 'http://192.168.16.110:31836'
    PROM_URL    = 'http://192.168.19.42:30091'
  }
  stages {
    stage('Data source') {
      steps {
        withCredentials([string(credentialsId: 'grafana-token', variable: 'GF_TOKEN')]) {
          sh '''
            set -e
            BODY='{"uid":"shopglobal-prom","name":"ShopGlobal-Prometheus","type":"prometheus","access":"proxy","url":"'$PROM_URL'","isDefault":false}'
            code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $GF_TOKEN" $GRAFANA_URL/api/datasources/uid/shopglobal-prom)
            if [ "$code" = "200" ]; then M=PUT; U=$GRAFANA_URL/api/datasources/uid/shopglobal-prom; else M=POST; U=$GRAFANA_URL/api/datasources; fi
            curl -sf -X $M -H "Authorization: Bearer $GF_TOKEN" -H 'Content-Type: application/json' -d "$BODY" "$U"; echo
          '''
        }
      }
    }
    stage('Dashboard') {
      steps {
        withCredentials([string(credentialsId: 'grafana-token', variable: 'GF_TOKEN')]) {
          sh '''
            set -e
            printf '{"dashboard":' > /tmp/dash.json; cat grafana/kong-shopglobal.json >> /tmp/dash.json
            printf ',"overwrite":true,"message":"jenkins"}' >> /tmp/dash.json
            curl -sf -X POST -H "Authorization: Bearer $GF_TOKEN" -H 'Content-Type: application/json' -d @/tmp/dash.json $GRAFANA_URL/api/dashboards/db; echo
            rm -f /tmp/dash.json
          '''
        }
      }
    }
    stage('Check Prometheus targets') {
      steps {
        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
          sh 'curl -sf $PROM_URL/api/v1/targets | python3 -c "import sys,json;[print(t[\'labels\'][\'job\'],t[\'health\'],t[\'scrapeUrl\']) for t in json.load(sys.stdin)[\'data\'][\'activeTargets\']]"'
        }
      }
    }
  }
}
