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
  -d '{"basic_auth_enabled":false,"idp_mapping_enabled":false,"konnect_mapping_enabled":true}' "$URL"); echo "PATCH authentication-settings -> HTTP ${CODE}"
head -c 600 /tmp/auth.$$; echo
echo "--- portal settings ---"
CODE=$(curl -sS -o /tmp/auth.$$ -w '%{http_code}' -H "$H" "https://in.api.konghq.com/v3/portals/${PORTAL_ID}"); echo "GET portal -> HTTP ${CODE}"
jq -c '{name, authentication_enabled, rbac_enabled, auto_approve_developers, auto_approve_applications, default_domain, default_api_visibility, default_page_visibility}' /tmp/auth.$$ 2>/dev/null || head -c 400 /tmp/auth.$$; echo
echo "--- developers (no secrets) ---"
CODE=$(curl -sS -o /tmp/auth.$$ -w '%{http_code}' -H "$H" "https://in.api.konghq.com/v3/portals/${PORTAL_ID}/developers?page%5Bsize%5D=20"); echo "GET developers -> HTTP ${CODE}"
jq -c '(.data // .)[]? | {id, email, full_name, status, created_at}' /tmp/auth.$$ 2>/dev/null || head -c 400 /tmp/auth.$$; echo
rm -f /tmp/auth.$$
exit 0
