terraform {
  required_version = ">= 1.6"
  required_providers {
    konnect = {
      source  = "Kong/konnect"
      version = "~> 3.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
  # State is kept on the Jenkins host (outside the job workspace) so it survives builds.
  backend "local" {
    path = "/var/lib/jenkins/tfstate/shopglobal/uat/terraform.tfstate"
  }
}

provider "konnect" {
  personal_access_token = var.konnect_pat
  server_url            = var.konnect_server_url
}
