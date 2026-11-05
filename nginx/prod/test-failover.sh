#!/bin/bash
# Manual failover test for the PROD edge (run from any machine that can reach the virtual IP).
#   bash test-failover.sh <VIP> [host-name]
# 1. In a second terminal, on the server that currently holds the VIP, stop the edge:   sudo systemctl stop nginx
#    (keepalived then drops the VIP and the other server takes it, normally within 5 seconds).
# 2. Watch the codes below: a few failures at most, then 401 again. Then start it again: sudo systemctl start nginx
VIP=${1:?usage: test-failover.sh <VIP> [host-name]}
HOST=${2:-shopglobal-api.pronteff.com}
while true; do
  code=$(curl -sk -o /dev/null -m 2 -w '%{http_code}' --resolve "$HOST:8443:$VIP" "https://$HOST:8443/api/categories" || true)
  echo "$(date +%H:%M:%S)  HTTP ${code:-000}"
  sleep 1
done
