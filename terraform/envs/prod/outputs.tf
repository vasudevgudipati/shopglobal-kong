output "control_plane_id" {
  value = konnect_gateway_control_plane.this.id
}
output "control_plane_endpoint" {
  value = konnect_gateway_control_plane.this.config.control_plane_endpoint
}
output "telemetry_endpoint" {
  value = konnect_gateway_control_plane.this.config.telemetry_endpoint
}
output "dp_cert_pem" {
  value     = tls_self_signed_cert.dp.cert_pem
  sensitive = true
}
output "dp_key_pem" {
  value     = tls_private_key.dp.private_key_pem
  sensitive = true
}
