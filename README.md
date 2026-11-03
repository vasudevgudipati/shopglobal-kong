# shopglobal-kong
Konnect hybrid platform for ShopGlobal (org ShopGlobal, IN region). UAT first; PROD follows the same layout.
Everything is deployed by Jenkins. Nothing is applied by hand, except NGINX (nginx/uat) and Keycloak, which run separately.

Pipelines (one Jenkins job each, script path shown):
| Job | Script path | Runs | Does |
|---|---|---|---|
| shopglobal-uat-platform | jenkins/uat/platform.Jenkinsfile | rarely | Terraform API control plane and Dev Portal (with logo), API data plane (.41), AI data plane (.42) |
| shopglobal-uat-api-config | jenkins/uat/api-config.Jenkinsfile | every API change | decK diff + sync of apiops/uat/kong.yaml, catalog API + OpenAPI spec (Terraform), smoke test |
| shopglobal-uat-portal-config | jenkins/uat/portal-config.Jenkinsfile | after API or portal changes | Publishes the catalog API to the Dev Portal |
Run order for a new environment: platform, api-config, portal-config. Planned: ai-config. PROD uses the same layout under jenkins/prod with a manual approval stage.

Jenkins host: 192.168.19.42. Credentials: `konnect-pat`, `github-token`, `ai-gw-tls-crt`, `ai-gw-tls-key`.
Kubeconfigs on the Jenkins host: `/var/lib/jenkins/.kube/config` (.42, AI) and `config-api` (.41, API).

UAT
- API gateway: classic control plane `shopglobal-api-uat` (Terraform). Data plane on k3s 192.168.19.41,
  namespace `shopglobal-uat-api`, NodePort 30080/30443.
- AI gateway: Konnect AI Gateway `shopglobal-ai-uat` (created once in the Konnect UI; Terraform cannot create it yet).
  Data plane (kong/kong-ai-gateway) on k3s 192.168.19.42, namespace `shopglobal-uat-ai`, NodePort 30090/30444.
  Connection hostnames in `helm/uat/ai-gw-endpoints.env`.
