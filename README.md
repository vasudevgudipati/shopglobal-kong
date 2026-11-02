# shopglobal-kong
Konnect hybrid platform for ShopGlobal (org ShopGlobal, IN region). UAT first; PROD follows the same layout.
Everything is deployed by Jenkins (see Jenkinsfile). Nothing is applied by hand.

Jenkins host: 192.168.19.42. Credentials: `konnect-pat`, `github-token`, `ai-gw-tls-crt`, `ai-gw-tls-key`.
Kubeconfigs on the Jenkins host: `/var/lib/jenkins/.kube/config` (.42, AI) and `config-api` (.41, API).

UAT
- API gateway: classic control plane `shopglobal-api-uat` (Terraform). Data plane on k3s 192.168.19.41,
  namespace `shopglobal-uat-api`, NodePort 30080/30443.
- AI gateway: Konnect AI Gateway `shopglobal-ai-uat` (created once in the Konnect UI; Terraform cannot create it yet).
  Data plane (kong/kong-ai-gateway) on k3s 192.168.19.42, namespace `shopglobal-uat-ai`, NodePort 30090/30444.
  Connection hostnames in `helm/uat/ai-gw-endpoints.env`.
