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
            code=$(curl -s -o /tmp/gf.out -w '%{http_code}' -X $M -H "Authorization: Bearer $GF_TOKEN" -H 'Content-Type: application/json' -d "$BODY" "$U")
            echo "Grafana answered HTTP $code: $(cat /tmp/gf.out)"; rm -f /tmp/gf.out
            [ "$code" -lt 300 ]
          '''
        }
      }
    }
    stage('Dashboards') {
      steps {
        withCredentials([string(credentialsId: 'grafana-token', variable: 'GF_TOKEN')]) {
          sh '''
            set -e
            # Remove the old combined dashboard (uid shopglobal-kong); HTTP 404 just means it is already gone.
            code=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE -H "Authorization: Bearer $GF_TOKEN" $GRAFANA_URL/api/dashboards/uid/shopglobal-kong)
            echo "delete old combined dashboard -> HTTP $code"
            for f in grafana/kong-api-gateway.json grafana/kong-ai-gateway.json; do
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
    stage('Check Prometheus targets') {
      steps {
        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
          sh 'curl -sf $PROM_URL/api/v1/targets | python3 -c "import sys,json;[print(t[\'labels\'][\'job\'],t[\'health\'],t[\'scrapeUrl\']) for t in json.load(sys.stdin)[\'data\'][\'activeTargets\']]"'
        }
      }
    }
  }
}
