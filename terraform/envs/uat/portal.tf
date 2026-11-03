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

# Home page. Without a published home page a new portal answers 404.
resource "konnect_portal_page" "home" {
  portal_id   = konnect_portal.this.id
  slug        = "/"
  title       = "ShopGlobal Developer Portal"
  description = "Home page: lists the published APIs"
  status      = "published"
  visibility  = "public"
  content     = file("${path.module}/../../../portal/uat/home.md")
}

# API listing page: shows every API published to this portal (the portal-config pipeline publishes them).
resource "konnect_portal_page" "apis" {
  portal_id   = konnect_portal.this.id
  slug        = "/apis"
  title       = "APIs"
  description = "Browse the ShopGlobal APIs"
  status      = "published"
  visibility  = "public"
  content     = file("${path.module}/../../../portal/uat/apis.md")
}

# Branding: colours from the UAT logo (purple #310068, teal accent #5eb4a4) and the top/bottom menus.
resource "konnect_portal_customization" "this" {
  portal_id = konnect_portal.this.id

  theme = {
    mode = "light"
    colors = {
      primary = "#310068"
    }
  }

  menu = {
    main = [
      { title = "APIs", path = "/apis", external = false, visibility = "public" },
    ]
    footer_sections = []
    footer_bottom = [
      { title = "ShopGlobal UAT - for testing only", path = "/", external = false, visibility = "public" },
    ]
  }
}

# Page colours from the UAT logo: deep purple (#310068) header/hero so the logo blends in, teal (#5eb4a4) accents and buttons.
resource "konnect_portal_appearance" "this" {
  portal_id         = konnect_portal.this.id
  theme_name        = "custom"
  use_custom_fonts  = false

  custom_theme = {
    colors = {
      button = {
        primary_fill = { value = "#5eb4a4" }
        primary_text = { value = "#1e0044" }
      }
      section = {
        header   = { value = "#310068" }
        hero     = { value = "#310068" }
        footer   = { value = "#1e0044" }
        body     = { value = "#f7f5fb" }
        accent   = { value = "#5eb4a4" }
        stroke   = { value = "#d9d0e8" }
        tertiary = { value = "#ece7f5" }
      }
      text = {
        header    = { value = "#ffffff" }
        hero      = { value = "#ffffff" }
        footer    = { value = "#e6e0f0" }
        headings  = { value = "#310068" }
        link      = { value = "#5a2ca0" }
        accent    = { value = "#2f8f7d" }
        primary   = { value = "#1f1633" }
        secondary = { value = "#5f5675" }
      }
    }
  }
}

output "portal_id" {
  value = konnect_portal.this.id
}
