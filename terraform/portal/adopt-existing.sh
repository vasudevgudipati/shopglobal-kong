#!/bin/bash
# Run inside terraform/portal/<env>. Usage: adopt-existing.sh <platform terraform.tfstate>
# If the portal already has an OIDC identity provider that Terraform does not manage yet (for example one
# created by hand in the Konnect UI), bring it under Terraform management so the job can correct its settings.
# Only reads from Konnect and writes the local Terraform state. Prints no secrets.
set -eu
STATE_FILE="$1"
PORTAL_ID=$(jq -r '.outputs.portal_id.value' "$STATE_FILE")
[ -n "$PORTAL_ID" ] && [ "$PORTAL_ID" != "null" ] || { echo "portal_id not found in $STATE_FILE"; exit 1; }
terraform init -input=false >/dev/null
if terraform state list 2>/dev/null | grep -qx 'konnect_portal_identity_provider.keycloak'; then
  echo "Identity provider is already managed by Terraform."
  exit 0
fi
RESP=$(curl -sS -H "Authorization: Bearer ${TF_VAR_konnect_pat}" "https://in.api.konghq.com/v3/portals/${PORTAL_ID}/identity-providers")
if ! echo "$RESP" | jq -e '.data' >/dev/null 2>&1; then
  echo "Could not list identity providers. Konnect answered:"
  echo "$RESP" | jq -r '.message // .detail // .title // .' 2>/dev/null | head -5
  exit 1
fi
echo "Identity providers found on the portal (no secrets shown):"
echo "$RESP" | jq -c '.data[] | {id, type, enabled, client_id: .config.oidc_identity_provider_config.client_id, issuer: .config.oidc_identity_provider_config.issuer_url}'
COUNT=$(echo "$RESP" | jq '[.data[] | select(.type=="oidc")] | length')
if [ "$COUNT" = "0" ]; then echo "No OIDC provider exists, Terraform will create it."; exit 0; fi
if [ "$COUNT" != "1" ]; then echo "More than one OIDC provider exists, import by hand."; exit 1; fi
IDP_ID=$(echo "$RESP" | jq -r '.data[] | select(.type=="oidc") | .id')
echo "Importing provider ${IDP_ID} into Terraform state."
terraform import -input=false konnect_portal_identity_provider.keycloak "{\"id\":\"${IDP_ID}\",\"portal_id\":\"${PORTAL_ID}\"}"
