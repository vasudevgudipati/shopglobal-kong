# Dev Portal for ShopGlobal PROD (empty shell + branding). APIs are published by the portal-config pipeline.
variable "portal_name" {
  type    = string
  default = "shopglobal-prod"
}

variable "portal_display_name" {
  type    = string
  default = "ShopGlobal Developer Portal (PROD)"
}

resource "konnect_portal" "this" {
  name         = var.portal_name
  display_name = var.portal_display_name
  description  = "ShopGlobal PROD - developer portal for the marketplace APIs"

  # Login required: visitors are sent to the Keycloak sign-in (identity provider is set by the portal-config pipeline).
  authentication_enabled    = true
  rbac_enabled              = false
  auto_approve_developers   = true
  auto_approve_applications = false
  default_api_visibility    = "private"
  default_page_visibility   = "private"
  force_destroy             = "false"
}

resource "konnect_portal_logo" "this" {
  portal_id = konnect_portal.this.id
  data      = "data:image/png;base64,${filebase64("${path.module}/../../../portal/prod/assets/logo.png")}"
}

resource "konnect_portal_favicon" "this" {
  portal_id = konnect_portal.this.id
  data      = "data:image/png;base64,${filebase64("${path.module}/../../../portal/prod/assets/favicon.png")}"
}

# Home page. Without a published home page a new portal answers 404.
resource "konnect_portal_page" "home" {
  portal_id   = konnect_portal.this.id
  slug        = "/"
  title       = "ShopGlobal Developer Portal"
  description = "Home page: lists the published APIs"
  status      = "published"
  visibility  = "private"
  content     = file("${path.module}/../../../portal/prod/home.md")
}

# API listing page: shows every API published to this portal (the portal-config pipeline publishes them).
resource "konnect_portal_page" "apis" {
  portal_id   = konnect_portal.this.id
  slug        = "/apis"
  title       = "APIs"
  description = "Browse the ShopGlobal APIs"
  status      = "published"
  visibility  = "private"
  content     = file("${path.module}/../../../portal/prod/apis.md")
}

# Branding: colours from the PROD logo (navy #1e1a41, orange accent #e5831c) and the top/bottom menus.
resource "konnect_portal_customization" "this" {
  portal_id = konnect_portal.this.id

  # Header, footer and page colours (the portal theme only exposes one colour, so the rest is CSS).
  css = file("${path.module}/../../../portal/prod/theme.css")

  theme = {
    mode = "light"
    colors = {
      primary = "#1e1a41"
    }
  }

  menu = {
    main = [
      { title = "APIs", path = "/apis", external = false, visibility = "private" },
    ]
    footer_sections = []
    footer_bottom = [
      { title = "ShopGlobal PROD", path = "/", external = false, visibility = "private" },
    ]
  }
}

output "portal_id" {
  value = konnect_portal.this.id
}
