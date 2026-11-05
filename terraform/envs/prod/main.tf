resource "konnect_gateway_control_plane" "this" {
  name         = var.control_plane_name
  description  = "ShopGlobal PROD - API gateway"
  cluster_type = "CLUSTER_TYPE_CONTROL_PLANE"
  auth_type    = "pinned_client_certs"
  labels = {
    customer = "shopglobal"
    env      = "prod"
  }
}

# Sample self-signed data plane certificate (to be replaced by a CA-issued one later).
resource "tls_private_key" "dp" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "tls_self_signed_cert" "dp" {
  private_key_pem       = tls_private_key.dp.private_key_pem
  validity_period_hours = 8760
  allowed_uses          = ["digital_signature", "key_encipherment", "client_auth", "server_auth"]
  subject {
    common_name = "kong-dp-prod.shopglobal"
  }
}

resource "konnect_gateway_data_plane_client_certificate" "dp" {
  control_plane_id = konnect_gateway_control_plane.this.id
  cert             = tls_self_signed_cert.dp.cert_pem
}
