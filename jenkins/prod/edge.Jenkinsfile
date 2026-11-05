// ShopGlobal Kong - PROD EDGE pipeline: two NGINX instances sharing one virtual IP (keepalived).
// Jenkins script path: jenkins/prod/edge.Jenkinsfile
// Hosts: 192.168.19.5 (MASTER, priority 150) and kongcp 192.168.19.4 (BACKUP, priority 100). Installed over SSH (credential edge-ssh-prod).
// NGINX terminates HTTPS on 8443 and routes by host name to: API gateway (kongdp1/kongdp2 NodePort 30080), and optionally web, agent and AI gateway.
// TLS: the wildcard certificate of the UAT NGINX, from the Jenkins credentials edge-tls-crt-prod (fullchain.pem) and edge-tls-key-prod (privkey.pem). No self-signed fallback.
// APPLY unticked: validates the inputs and the certificate, renders the configuration and shows what would change on each server. APPLY ticked: installs and applies it.
def withEdgeKey(Closure body) {
  withCredentials([sshUserPrivateKey(credentialsId: 'edge-ssh-prod', keyFileVariable: 'SSH_KEY', usernameVariable: 'SSH_USER')]) { body() }
}

pipeline {
  agent any
  options { disableConcurrentBuilds(); timestamps() }
  parameters {
    booleanParam(name: 'APPLY', defaultValue: false, description: 'Tick to install/apply on the edge servers. Left unticked, the job only validates and shows the changes.')
    string(name: 'VIP', defaultValue: '', description: 'Virtual IP shared by the two NGINX servers (free address in 192.168.16.0/22). Required.')
    string(name: 'VRID', defaultValue: '61', description: 'VRRP router id (1-255). Change it only if another VRRP group on this network already uses 61.')
    string(name: 'API_HOST', defaultValue: 'shopglobal-api.pronteff.com', description: 'Host name of the API gateway')
    string(name: 'WEB_HOST', defaultValue: 'shopglobal-web.pronteff.com', description: 'Host name of the web app')
    string(name: 'WEB_UPSTREAM', defaultValue: '', description: 'Web app address(es) as host:port, space separated. Empty = not published yet.')
    string(name: 'AGENT_HOST', defaultValue: 'shopglobal-agent.pronteff.com', description: 'Host name of the chat agent')
    string(name: 'AGENT_UPSTREAM', defaultValue: '', description: 'Agent address(es) as host:port, space separated. Empty = not published yet.')
    string(name: 'AI_HOST', defaultValue: 'shopglobal-ai.pronteff.com', description: 'Host name of the AI gateway')
    string(name: 'AI_UPSTREAM', defaultValue: '', description: 'AI gateway node addresses as host:port, space separated (NodePort of the AI data plane). Empty = not published yet.')
  }
  environment {
    EDGE_HOSTS    = '192.168.19.5:MASTER:150 192.168.19.4:BACKUP:100'
    EDGE_IFACE    = 'ens192'
    EDGE_PREFIX   = '22'
    HTTPS_PORT    = '8443'
    API_UPSTREAMS = '192.168.19.1:30080 192.168.19.2:30080'
    OUT           = "${WORKSPACE}/edge-out"
  }
  stages {
    stage('Run mode') {
      steps {
        script {
          // First build after the pipeline file changed: Jenkins has not registered the parameters yet, so they arrive empty.
          env.FIRST_RUN = (params.VRID == null) ? 'true' : 'false'
          if (env.FIRST_RUN == 'true') {
            echo "FIRST RUN: the parameters are now registered. Nothing else ran. Open the job and click 'Build with Parameters'."
            currentBuild.displayName = "#${env.BUILD_NUMBER} REGISTER"
            return
          }
          def mode = params.APPLY ? 'APPLY - changes WILL be made to the PROD edge servers' : 'PLAN ONLY - nothing will be changed'
          echo "=============================================================="
          echo " APPLY selected : ${params.APPLY}"
          echo " Mode           : ${mode}"
          echo " Virtual IP     : ${params.VIP ?: '(not set)'}"
          echo " Published      : api=${params.API_HOST}  web=${params.WEB_UPSTREAM ? 'yes' : 'no'}  agent=${params.AGENT_UPSTREAM ? 'yes' : 'no'}  ai=${params.AI_UPSTREAM ? 'yes' : 'no'}"
          echo "=============================================================="
          currentBuild.displayName = "#${env.BUILD_NUMBER} " + (params.APPLY ? 'APPLY' : 'PLAN')
        }
      }
    }
    stage('Validate inputs') {
      when { expression { return env.FIRST_RUN != 'true' } }
      steps {
        sh '''
          set -e
          if [ -z "$VIP" ]; then
            if [ "$APPLY" = "true" ]; then echo "ERROR: VIP is required to apply"; exit 1; fi
            echo "NOTE: VIP is empty - the plan below uses a placeholder"
          elif ! echo "$VIP" | grep -Eq '^([0-9]{1,3}\\.){3}[0-9]{1,3}$'; then echo "ERROR: VIP '$VIP' is not an IPv4 address"; exit 1; fi
          echo "$VRID" | grep -Eq '^[0-9]+$' && [ "$VRID" -ge 1 ] && [ "$VRID" -le 255 ] || { echo "ERROR: VRID must be 1-255"; exit 1; }
          for h in $EDGE_HOSTS; do [ "${h%%:*}" != "$VIP" ] || { echo "ERROR: VIP must not be a server address"; exit 1; }; done
        '''
      }
    }
    stage('Render configuration') {
      when { expression { return env.FIRST_RUN != 'true' } }
      steps {
        sh '''
          set -e
          umask 077
          rm -rf "$OUT"; mkdir -p "$OUT"
          export API_HOST WEB_HOST AGENT_HOST AI_HOST WEB_UPSTREAM AGENT_UPSTREAM AI_UPSTREAM API_UPSTREAMS HTTPS_PORT
          bash nginx/prod/render-nginx.sh > "$OUT/nginx.conf"
          VIPV=${VIP:-192.0.2.1}
          for h in $EDGE_HOSTS; do
            ip=${h%%:*}; rest=${h#*:}; state=${rest%%:*}; prio=${rest#*:}
            peer=""; for o in $EDGE_HOSTS; do [ "${o%%:*}" != "$ip" ] && peer=${o%%:*}; done
            sed -e "s#__STATE__#$state#; s#__PRIORITY__#$prio#; s#__IFACE__#$EDGE_IFACE#g; s#__VRID__#$VRID#; s#__SELF_IP__#$ip#; s#__PEER_IP__#$peer#; s#__VIP__#$VIPV#; s#__PREFIX__#$EDGE_PREFIX#" nginx/prod/keepalived.conf.tpl > "$OUT/keepalived-$ip.conf"
          done
          echo "----- nginx.conf -----"; cat "$OUT/nginx.conf"
          echo "----- keepalived (first server) -----"; cat "$OUT/keepalived-192.168.19.5.conf"
        '''
      }
    }
    stage('Check TLS certificate') {
      when { expression { return env.FIRST_RUN != 'true' } }
      steps {
        // The same wildcard certificate as the UAT NGINX (*.pronteff.com). It is kept only in Jenkins credentials (Secret file), never in Git:
        //   edge-tls-crt-prod = certificate chain (fullchain.pem: server certificate first, then the CA certificates)
        //   edge-tls-key-prod = private key (privkey.pem, no passphrase)
        withCredentials([file(credentialsId: 'edge-tls-crt-prod', variable: 'TLS_CRT_FILE'), file(credentialsId: 'edge-tls-key-prod', variable: 'TLS_KEY_FILE')]) {
          sh '''
            set -e
            umask 077
            mkdir -p "$OUT/tls"
            cp "$TLS_CRT_FILE" "$OUT/tls/tls.crt"; cp "$TLS_KEY_FILE" "$OUT/tls/tls.key"
            C="$OUT/tls/tls.crt"; K="$OUT/tls/tls.key"
            openssl x509 -in "$C" -noout || { echo "ERROR: edge-tls-crt-prod is not a PEM certificate"; exit 1; }
            openssl pkey -in "$K" -noout -passin pass: || { echo "ERROR: edge-tls-key-prod is not a usable private key (it must be PEM without a passphrase)"; exit 1; }
            A=$(openssl x509 -in "$C" -noout -pubkey | openssl sha256)
            B=$(openssl pkey -in "$K" -pubout | openssl sha256)
            [ "$A" = "$B" ] || { echo "ERROR: the private key does not belong to this certificate"; exit 1; }
            echo "subject : $(openssl x509 -in "$C" -noout -subject)"
            echo "issuer  : $(openssl x509 -in "$C" -noout -issuer)"
            echo "valid   : $(openssl x509 -in "$C" -noout -startdate) / $(openssl x509 -in "$C" -noout -enddate)"
            echo "names   : $(openssl x509 -in "$C" -noout -ext subjectAltName | tail -n +2 | tr -d ' ')"
            [ "$(openssl x509 -in "$C" -noout -subject_hash)" != "$(openssl x509 -in "$C" -noout -issuer_hash)" ] || { echo "ERROR: this is a self-signed certificate. Use the CA-issued wildcard certificate that the UAT NGINX uses."; exit 1; }
            openssl x509 -in "$C" -noout -checkend 0 >/dev/null || { echo "ERROR: the certificate has expired"; exit 1; }
            openssl x509 -in "$C" -noout -checkend 2592000 >/dev/null || echo "WARNING: the certificate expires within 30 days - renew it"
            for hn in $API_HOST $WEB_HOST $AGENT_HOST $AI_HOST; do
              openssl x509 -in "$C" -noout -checkhost "$hn" | grep -q "does match" || { echo "ERROR: the certificate does not cover $hn"; exit 1; }
              echo "covers  : $hn"
            done
            N=$(grep -c "BEGIN CERTIFICATE" "$C")
            [ "$N" -ge 2 ] || echo "WARNING: the file holds only the server certificate; use fullchain.pem so clients also receive the CA certificates"
            echo "certificate OK ($N certificate(s) in the file)"
          '''
        }
      }
    }
    stage('Show changes on the servers') {
      when { expression { return env.FIRST_RUN != 'true' } }
      steps {
        withEdgeKey {
          sh '''
            SSHO="-i $SSH_KEY -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$WORKSPACE/.edge_known_hosts -o ConnectTimeout=10"
            for h in $EDGE_HOSTS; do
              ip=${h%%:*}
              echo "===== $ip ====="
              if ! ssh $SSHO $SSH_USER@$ip 'sudo -n true' 2>/dev/null; then echo "ERROR: cannot SSH/sudo to $ip"; exit 1; fi
              echo "-- nginx.conf (current vs new):"
              ssh $SSHO $SSH_USER@$ip 'sudo cat /etc/nginx/nginx.conf 2>/dev/null || echo "(not installed yet)"' | diff -u - "$OUT/nginx.conf" | head -60 || true
              echo "-- keepalived.conf (current vs new):"
              ssh $SSHO $SSH_USER@$ip 'sudo cat /etc/keepalived/keepalived.conf 2>/dev/null || echo "(not installed yet)"' | diff -u - "$OUT/keepalived-$ip.conf" | head -40 || true
            done
          '''
        }
      }
    }
    stage('Apply to PROD edge (only when APPLY is ticked)') {
      when { expression { return env.FIRST_RUN != 'true' && params.APPLY } }
      stages {
        stage('Check the virtual IP is free') {
          steps {
            withEdgeKey {
              sh '''
                SSHO="-i $SSH_KEY -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$WORKSPACE/.edge_known_hosts -o ConnectTimeout=10"
                held=0
                for h in $EDGE_HOSTS; do
                  ip=${h%%:*}
                  if ssh $SSHO $SSH_USER@$ip "ip -4 -o addr show dev $EDGE_IFACE | grep -q ' $VIP/'"; then held=1; echo "$VIP is already held by $ip (re-run)"; fi
                done
                if [ $held = 0 ] && ping -c1 -W1 "$VIP" >/dev/null 2>&1; then echo "ERROR: $VIP answers ping and is not ours - choose another address"; exit 1; fi
                echo "OK"
              '''
            }
          }
        }
        stage('Install NGINX and keepalived') {
          steps {
            withEdgeKey {
              sh '''
                SSHO="-i $SSH_KEY -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$WORKSPACE/.edge_known_hosts -o ConnectTimeout=10"
                for h in $EDGE_HOSTS; do
                  ip=${h%%:*}; echo "===== $ip ====="
                  ssh $SSHO $SSH_USER@$ip 'sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nginx keepalived curl openssl && nginx -v 2>&1 && keepalived --version 2>&1 | head -1'
                done
              '''
            }
          }
        }
        stage('TLS certificate') {
          steps {
            withEdgeKey {
              sh '''
                set -e
                umask 077
                SSHO="-i $SSH_KEY -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$WORKSPACE/.edge_known_hosts -o ConnectTimeout=10"
                [ -s "$OUT/tls/tls.crt" ] && [ -s "$OUT/tls/tls.key" ] || { echo "ERROR: certificate files are missing (the Check TLS certificate stage did not run)"; exit 1; }
                for h in $EDGE_HOSTS; do
                  ip=${h%%:*}; echo "===== $ip ====="
                  scp $SSHO "$OUT/tls/tls.crt" "$OUT/tls/tls.key" $SSH_USER@$ip:/tmp/
                  ssh $SSHO $SSH_USER@$ip 'sudo install -d -m 755 /etc/nginx/tls && sudo install -m 644 /tmp/tls.crt /etc/nginx/tls/tls.crt && sudo install -m 600 -o root -g root /tmp/tls.key /etc/nginx/tls/tls.key && rm -f /tmp/tls.crt /tmp/tls.key && echo "certificate installed"'
                done
              '''
            }
          }
        }
        stage('Deploy NGINX') {
          steps {
            withEdgeKey {
              sh '''
                SSHO="-i $SSH_KEY -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$WORKSPACE/.edge_known_hosts -o ConnectTimeout=10"
                for h in $EDGE_HOSTS; do
                  ip=${h%%:*}; echo "===== $ip ====="
                  scp $SSHO "$OUT/nginx.conf" $SSH_USER@$ip:/tmp/edge-nginx.conf
                  ssh $SSHO $SSH_USER@$ip 'bash -s' <<'REMOTE'
set -e
sudo cp -a /etc/nginx/nginx.conf /etc/nginx/nginx.conf.prev 2>/dev/null || true
sudo install -m 644 /tmp/edge-nginx.conf /etc/nginx/nginx.conf
rm -f /tmp/edge-nginx.conf
if ! sudo nginx -t; then
  echo "ERROR: new NGINX configuration is invalid - restoring the previous one"
  sudo cp -a /etc/nginx/nginx.conf.prev /etc/nginx/nginx.conf
  exit 1
fi
sudo systemctl enable nginx
if systemctl is-active --quiet nginx; then sudo systemctl reload nginx; else sudo systemctl start nginx; fi
sleep 1
curl -fsS --max-time 3 http://127.0.0.1:8081/healthz && echo " (nginx healthy)"
REMOTE
                done
              '''
            }
          }
        }
        stage('Deploy keepalived (virtual IP)') {
          steps {
            withEdgeKey {
              sh '''
                SSHO="-i $SSH_KEY -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$WORKSPACE/.edge_known_hosts -o ConnectTimeout=10"
                for h in $EDGE_HOSTS; do
                  ip=${h%%:*}; echo "===== $ip ====="
                  scp $SSHO "$OUT/keepalived-$ip.conf" $SSH_USER@$ip:/tmp/edge-keepalived.conf
                  scp $SSHO nginx/prod/check_nginx.sh $SSH_USER@$ip:/tmp/edge-check_nginx.sh
                  ssh $SSHO $SSH_USER@$ip 'bash -s' <<'REMOTE'
set -e
sudo install -m 755 -o root -g root /tmp/edge-check_nginx.sh /etc/keepalived/check_nginx.sh
sudo cp -a /etc/keepalived/keepalived.conf /etc/keepalived/keepalived.conf.prev 2>/dev/null || true
sudo install -m 644 /tmp/edge-keepalived.conf /etc/keepalived/keepalived.conf
rm -f /tmp/edge-keepalived.conf /tmp/edge-check_nginx.sh
sudo keepalived --config-test -f /etc/keepalived/keepalived.conf
sudo systemctl enable keepalived
if systemctl is-active --quiet keepalived; then sudo systemctl reload keepalived; else sudo systemctl start keepalived; fi
REMOTE
                done
              '''
            }
          }
        }
        stage('Verify') {
          steps {
            withEdgeKey {
              sh '''
                SSHO="-i $SSH_KEY -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$WORKSPACE/.edge_known_hosts -o ConnectTimeout=10"
                sleep 8
                holders=0
                for h in $EDGE_HOSTS; do
                  ip=${h%%:*}
                  if ssh $SSHO $SSH_USER@$ip "ip -4 -o addr show dev $EDGE_IFACE | grep -q ' $VIP/'"; then echo "virtual IP $VIP is on $ip"; holders=$((holders+1)); fi
                done
                [ "$holders" = "1" ] || { echo "ERROR: expected the virtual IP on exactly one server, found $holders"; exit 1; }
                bad=0
                for target in $VIP $(for h in $EDGE_HOSTS; do echo ${h%%:*}; done); do
                  code=$(curl -sk -o /dev/null -m 10 -w '%{http_code}' --resolve "$API_HOST:$HTTPS_PORT:$target" "https://$API_HOST:$HTTPS_PORT/api/categories" || true)
                  echo "https://$API_HOST:$HTTPS_PORT via $target -> HTTP $code (401 = gateway reached, token required)"
                  [ "$code" = "401" ] || bad=1
                done
                [ $bad = 0 ] || { echo "ERROR: unexpected answer from the edge"; exit 1; }
                for target in $VIP $(for h in $EDGE_HOSTS; do echo ${h%%:*}; done); do
                  echo "certificate served by $target:"
                  echo | openssl s_client -connect $target:$HTTPS_PORT -servername $API_HOST 2>/dev/null | openssl x509 -noout -subject -issuer -enddate
                done
                echo "OK. Next: point DNS for $API_HOST (and the other names) at $VIP, then run nginx/prod/test-failover.sh"
              '''
            }
          }
        }
      }
    }
  }
  post {
    always { sh 'rm -rf "$OUT" "$WORKSPACE/.edge_known_hosts"' }
  }
}
