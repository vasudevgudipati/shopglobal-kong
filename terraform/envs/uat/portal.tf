# Dev Portal for ShopGlobal UAT (empty shell + branding). APIs are published by the portal-config pipeline.
variable "portal_name" {
  type    = string
  default = "shopglobal-uat"
}

variable "portal_display_name" {
  type    = string
  default = "ShopGlobal Developer Portal (UAT)"
}

resource "konnect_portal" "this" {
  name         = var.portal_name
  display_name = var.portal_display_name
  description  = "ShopGlobal UAT - developer portal for the marketplace APIs"

  # Login is switched on later, when Keycloak is wired in.
  authentication_enabled    = false
  rbac_enabled              = false
  auto_approve_developers   = true
  auto_approve_applications = false
  default_api_visibility    = "public"
  default_page_visibility   = "public"
  force_destroy             = "false"
}

resource "konnect_portal_logo" "this" {
  portal_id = konnect_portal.this.id
  data      = "data:image/png;base64,${filebase64("${path.module}/../../../portal/uat/assets/logo.png")}"
}

resource "konnect_portal_favicon" "this" {
  portal_id = konnect_portal.this.id
  data      = "data:image/png;base64,${filebase64("${path.module}/../../../portal/uat/assets/favicon.png")}"
}

output "portal_id" {
  value = konnect_portal.this.id
}
