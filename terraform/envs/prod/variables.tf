variable "konnect_pat" {
  description = "Konnect personal access token (supplied by Jenkins credential konnect-pat as TF_VAR_konnect_pat)"
  type        = string
  sensitive   = true
}

variable "konnect_server_url" {
  type    = string
  default = "https://in.api.konghq.com"
}

variable "control_plane_name" {
  type    = string
  default = "shopglobal-api-prod"
}
