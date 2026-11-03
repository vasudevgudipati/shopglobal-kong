# Publishes the catalog API to the UAT Dev Portal. Reads the portal id (platform state) and the API id (catalog state).
data "terraform_remote_state" "platform" {
  backend = "local"
  config  = { path = "/var/lib/jenkins/tfstate/shopglobal/uat/terraform.tfstate" }
}

data "terraform_remote_state" "catalog" {
  backend = "local"
  config  = { path = "/var/lib/jenkins/tfstate/shopglobal/uat-catalog/terraform.tfstate" }
}

resource "konnect_api_publication" "marketplace" {
  api_id     = data.terraform_remote_state.catalog.outputs.api_id
  portal_id  = data.terraform_remote_state.platform.outputs.portal_id
  visibility = "public"
}
