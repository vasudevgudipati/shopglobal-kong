# Publishes the catalog API to the PROD Dev Portal (visible only after login) and sets up Keycloak single sign-on.
# Reads the portal id (platform state) and the API id (catalog state).
data "terraform_remote_state" "platform" {
  backend = "local"
  config  = { path = "/var/lib/jenkins/tfstate/shopglobal/prod/terraform.tfstate" }
}

data "terraform_remote_state" "catalog" {
  backend = "local"
  config  = { path = "/var/lib/jenkins/tfstate/shopglobal/prod-catalog/terraform.tfstate" }
}

locals {
  portal_id = data.terraform_remote_state.platform.outputs.portal_id
}

# The API is listed only for signed-in users.
resource "konnect_api_publication" "marketplace" {
  api_id     = data.terraform_remote_state.catalog.outputs.api_id
  portal_id  = local.portal_id
  visibility = "private"
}

# Single sign-on with Keycloak (realm demo). Create the Keycloak client first (see variables below) and
# add the redirect URL that Konnect shows for this portal (Dev Portal > Settings > Identity) to that client.
resource "konnect_portal_identity_provider" "keycloak" {
  portal_id = local.portal_id
  type      = "oidc"
  enabled   = true
  config = {
    oidc_identity_provider_config = {
      issuer_url    = var.oidc_issuer
      client_id     = var.oidc_client_id
      client_secret = var.oidc_client_secret
      scopes        = ["openid", "email", "profile"]
    }
  }
}

# Keycloak is the only way in: portal self-registration with e-mail and password is switched off.
resource "konnect_portal_auth" "this" {
  portal_id          = local.portal_id
  basic_auth_enabled      = false
  # Konnect requires one mapping mode to stay on. Konnect mapping does not read a "groups" claim from Keycloak, so the sign-in cannot fail on a missing claim.
  idp_mapping_enabled     = false
  konnect_mapping_enabled = true
  depends_on              = [konnect_portal_identity_provider.keycloak]
}
