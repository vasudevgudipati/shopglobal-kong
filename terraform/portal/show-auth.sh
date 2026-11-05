#!/bin/bash
# Diagnostic (never fails the job): shows the portal's sign-in settings and Konnect's real answer to the change Terraform makes.
# Usage: show-auth.sh <platform terraform.tfstate>
STATE_FILE="$1"
PORTAL_ID=$(jq -r '.outputs.portal_id.value' "$STATE_FILE")
URL="https://in.api.konghq.com/v3/portals/${PORTAL_ID}/authentication-settings"
H="Authorization: Bearer ${TF_VAR_konnect_pat}"
echo "--- current sign-in settings ---"
CODE=$(curl -sS -o /tmp/auth.$$ -w '%{http_code}' -H "$H" "$URL"); echo "GET authentication-settings -> HTTP ${CODE}"
head -c 600 /tmp/auth.$$; echo
echo "--- the change Terraform applies ---"
CODE=$(curl -sS -o /tmp/auth.$$ -w '%{http_code}' -X PATCH -H "$H" -H 'Content-Type: application/json' \
  -d '{"basic_auth_enabled":false,"idp_mapping_enabled":false,"konnect_mapping_enabled":false}' "$URL"); echo "PATCH authentication-settings -> HTTP ${CODE}"
head -c 600 /tmp/auth.$$; echo
rm -f /tmp/auth.$$
exit 0
