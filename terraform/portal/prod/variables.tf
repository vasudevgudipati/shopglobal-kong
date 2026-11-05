variable "konnect_pat" {
  type      = string
  sensitive = true
}

variable "konnect_server_url" {
  type    = string
  default = "https://in.api.konghq.com"
}

variable "oidc_issuer" {
  description = "Keycloak realm issuer URL"
  type        = string
  default     = "https://marmalade-ivy-crazed.ngrok-free.dev/realms/demo"
}

variable "oidc_client_id" {
  description = "Keycloak client used by this portal (confidential client, standard flow)"
  type        = string
  default     = "kong-portal-prod"
}

variable "oidc_client_secret" {
  description = "Secret of that Keycloak client (Jenkins credential, passed as TF_VAR_oidc_client_secret)"
  type        = string
  sensitive   = true
}
