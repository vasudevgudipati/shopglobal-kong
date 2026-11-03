#!/bin/bash
# ShopGlobal UAT - NGINX HTTPS front on server .41 (runs as a pod in the k3s cluster, port 8443).
# Run as root on 192.168.19.41:   bash install-edge.sh
# Safe to run again: it re-applies everything and restarts NGINX to pick up changes.
set -euo pipefail

# ---- change these if you want different names ----
NS=shopglobal-edge-uat
NODE_IP=192.168.19.41
API_HOST=api-uat.shopglobal.local     # -> API gateway (Kong, this server)
WEB_HOST=shop-uat.shopglobal.local    # -> web frontend (shopglobal-web)
AI_HOST=ai-uat.shopglobal.local       # -> AI gateway on the other server (.42)
API_UPSTREAM=http://kong-api-dp-kong-proxy.shopglobal-uat-api.svc.cluster.local:80
WEB_UPSTREAM=http://shopglobal-web.shopglobal-app-uat.svc.cluster.local:8080
AI_UPSTREAM=http://192.168.19.42:30090
HTTPS_PORT=8443
# --------------------------------------------------

kubectl get ns "$NS" >/dev/null 2>&1 || kubectl create ns "$NS"

# Sample (self-signed) certificate, created once. Replace the secret later with a real one.
if ! kubectl -n "$NS" get secret edge-tls >/dev/null 2>&1; then
  T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
  openssl req -x509 -newkey rsa:2048 -nodes -days 365 -keyout "$T/tls.key" -out "$T/tls.crt" \
    -subj "/CN=shopglobal-uat" \
    -addext "subjectAltName=DNS:$API_HOST,DNS:$WEB_HOST,DNS:$AI_HOST,IP:$NODE_IP"
  kubectl -n "$NS" create secret tls edge-tls --cert="$T/tls.crt" --key="$T/tls.key"
fi

CONF=$(mktemp)
cat > "$CONF" <<EOF
worker_processes auto;
events { worker_connections 1024; }
http {
  server_tokens off;
  client_max_body_size 20m;
  ssl_protocols TLSv1.2 TLSv1.3;
  ssl_certificate     /etc/nginx/tls/tls.crt;
  ssl_certificate_key /etc/nginx/tls/tls.key;
  map \$http_upgrade \$connection_upgrade { default upgrade; '' close; }

  # Unknown host names get nothing
  server { listen $HTTPS_PORT ssl default_server; server_name _; return 404; }

  server {
    listen $HTTPS_PORT ssl;
    server_name $API_HOST;
    location / {
      proxy_pass $API_UPSTREAM;
      proxy_set_header Host \$host;
      proxy_set_header X-Real-IP \$remote_addr;
      proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto https;
      proxy_read_timeout 60s;
    }
  }
  server {
    listen $HTTPS_PORT ssl;
    server_name $WEB_HOST;
    location / {
      proxy_pass $WEB_UPSTREAM;
      proxy_set_header Host \$host;
      proxy_set_header X-Real-IP \$remote_addr;
      proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header Upgrade \$http_upgrade;
      proxy_set_header Connection \$connection_upgrade;
    }
  }
  server {
    listen $HTTPS_PORT ssl;
    server_name $AI_HOST;
    location / {
      proxy_pass $AI_UPSTREAM;
      proxy_set_header Host \$host;
      proxy_set_header X-Real-IP \$remote_addr;
      proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto https;
      proxy_buffering off;          # streaming model responses
      proxy_read_timeout 300s;
    }
  }
}
EOF
kubectl -n "$NS" create configmap edge-nginx --from-file=nginx.conf="$CONF" --dry-run=client -o yaml | kubectl apply -f -
rm -f "$CONF"

cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: edge-nginx
  namespace: $NS
spec:
  replicas: 1
  selector:
    matchLabels: { app: edge-nginx }
  template:
    metadata:
      labels: { app: edge-nginx }
    spec:
      containers:
        - name: nginx
          image: nginx:1.27-alpine
          ports:
            - containerPort: $HTTPS_PORT
              hostPort: $HTTPS_PORT
          volumeMounts:
            - { name: conf, mountPath: /etc/nginx/nginx.conf, subPath: nginx.conf }
            - { name: tls,  mountPath: /etc/nginx/tls, readOnly: true }
          readinessProbe:
            tcpSocket: { port: $HTTPS_PORT }
            initialDelaySeconds: 3
          resources:
            requests: { cpu: 50m, memory: 64Mi }
            limits:   { cpu: 500m, memory: 256Mi }
      volumes:
        - name: conf
          configMap: { name: edge-nginx }
        - name: tls
          secret: { secretName: edge-tls }
EOF

kubectl -n "$NS" rollout restart deploy/edge-nginx
kubectl -n "$NS" rollout status deploy/edge-nginx --timeout=120s
echo
echo "NGINX is up on https://$NODE_IP:$HTTPS_PORT"
echo "Test:  curl -k --resolve $API_HOST:$HTTPS_PORT:$NODE_IP https://$API_HOST:$HTTPS_PORT/api/categories"
