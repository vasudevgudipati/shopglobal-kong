#!/bin/bash
# keepalived health check: exit 0 while NGINX answers on its local health endpoint, non-zero otherwise.
curl -fsS --max-time 2 http://127.0.0.1:8081/healthz >/dev/null
