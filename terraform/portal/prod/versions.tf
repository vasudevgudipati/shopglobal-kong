terraform {
  required_version = ">= 1.6"
  required_providers {
    konnect = {
      source  = "Kong/konnect"
      version = "~> 3.0"
    }
  }
  backend "local" {
    path = "/var/lib/jenkins/tfstate/shopglobal/prod-portal/terraform.tfstate"
  }
}

provider "konnect" {
  personal_access_token = var.konnect_pat
  server_url            = var.konnect_server_url
}
