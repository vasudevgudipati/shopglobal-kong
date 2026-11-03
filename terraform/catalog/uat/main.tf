# API catalog entry for the ShopGlobal marketplace API (UAT): the API, its OpenAPI spec and a getting-started page.
# Linking it to the gateway (konnect_api_implementation) is added with authentication, later.
resource "konnect_api" "marketplace" {
  name        = "ShopGlobal Marketplace API"
  slug        = "shopglobal-marketplace-api"
  description = "Products, orders, returns and saved addresses for the ShopGlobal storefront and agents."
  labels = {
    customer = "shopglobal"
    env      = "uat"
  }
}

resource "konnect_api_version" "v1" {
  api_id  = konnect_api.marketplace.id
  version = "1.0.0"
  spec = {
    content = file("${path.module}/../../../apiops/uat/openapi.yaml")
  }
}

resource "konnect_api_document" "getting_started" {
  api_id  = konnect_api.marketplace.id
  title   = "Getting started"
  slug    = "getting-started"
  status  = "published"
  content = file("${path.module}/../../../apiops/uat/getting-started.md")
}
