variable "konnect_pat" {
  type      = string
  sensitive = true
}

variable "konnect_server_url" {
  type    = string
  default = "https://in.api.konghq.com"
}
