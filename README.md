# shopglobal-kong
Konnect hybrid platform for ShopGlobal (org ShopGlobal, IN region). UAT first; PROD follows the same layout.
Everything is deployed by Jenkins. Nothing is applied by hand, except NGINX (nginx/uat) and Keycloak, which run separately.

Pipelines (one Jenkins job each, script path shown):
| Job | Script path | Runs | Does |
|---|---|---|---|
| shopglobal-uat-platform | jenkins/uat/platform.Jenkinsfile | rarely | Terraform API control plane and Dev Portal (with logo), API data plane (.41), Redis (.41), Redis Stack and AI data plane (.42) |
| shopglobal-uat-api-config | jenkins/uat/api-config.Jenkinsfile | every API change | decK diff + sync of apiops/uat/kong.yaml, catalog API + OpenAPI spec (Terraform), smoke test |
| shopglobal-uat-ai-config | jenkins/uat/ai-config.Jenkinsfile | every AI gateway change | kongctl diff + apply of aigw/uat/ai-gateway.yaml: providers, models (Gemini, OpenAI, Ollama fallback), MCP server |
| shopglobal-uat-portal-config | jenkins/uat/portal-config.Jenkinsfile | after API or portal changes | Publishes the catalog API to the Dev Portal |
Run order for a new environment: platform, api-config, ai-config, portal-config. PROD uses the same layout under jenkins/prod with a manual approval stage.

Jenkins host: 192.168.19.42. Credentials: `konnect-pat`, `github-token`, `ai-gw-tls-crt`, `ai-gw-tls-key`,
`keycloak-introspect-basic` (Secret text: `Basic <base64 of client_id:client_secret>` of the Keycloak client `kong-dev-portal`),
`redis-password` (Secret text: letters and digits only),
`gemini-api-key`, `openai-api-key` (Secret text).
kongctl must be installed on the Jenkins host: `/usr/local/bin/kongctl` (the AI gateway is managed with kongctl, not decK or Terraform).
Kubeconfigs on the Jenkins host: `/var/lib/jenkins/.kube/config` (.42, AI) and `config-api` (.41, API).

UAT
- API gateway: classic control plane `shopglobal-api-uat` (Terraform). Data plane on k3s 192.168.19.41,
  namespace `shopglobal-uat-api`, NodePort 30080/30443.
- AI gateway: Konnect AI Gateway `shopglobal-ai-uat` (created once in the Konnect UI; Terraform cannot create it yet).
  Data plane (kong/kong-ai-gateway) on k3s 192.168.19.42, namespace `shopglobal-uat-ai`, NodePort 30090/30444.
  Connection hostnames in `helm/uat/ai-gw-endpoints.env`.

API plugins (apiops/uat/kong.yaml, deployed by the api-config pipeline)
- On the service (all routes): CORS, OAuth 2.0 introspection (Keycloak realm `demo`), IP restriction.
- Proxy Cache Advanced (Redis) on products (search, compare, get), categories, return-policy and addresses-list (300 s) and on orders-get (30 s). Order and address entries vary by the X-Customer-Id header. MCP tool calls use the internal route, which is not cached (a cancel must show at once).
- Per consumer (currently kong-dev-portal): Rate Limiting Advanced in Redis, 100 per minute on all routes and 10 per minute on the infrequent routes (orders-place, orders-cancel, orders-change-address, addresses-list).
- Consumer `custom_id` = Keycloak client id. Add a consumer here for every new application.
- Redis runs in namespace `shopglobal-uat-api` (platform pipeline, k8s/uat/redis.yaml). Canary (PROD only), ACL and HTTP log are not enabled in UAT yet.

AI gateway (aigw/uat/ai-gateway.yaml, deployed by the ai-config pipeline)
- Models on `/v1` (OpenAI format, no client auth yet; Keycloak later). The client picks the alias in the `model` field:
  - `shopglobal-auto`: semantic routing. Kong compares the customer's message with a description of each target (embeddings `text-embedding-3-small`, vectors in Redis Stack, threshold 0.7).
    Simple order-status / return-policy questions -> OpenAI gpt-5-mini; everything else, including unclear messages (the CATCHALL target) -> Gemini gemini-3.8-flash. No Ollama fallback on this alias.
  - `shopglobal-returns`: gpt-5-mini with the AI semantic cache (threshold 0.9, 1 h) and Ollama fallback. Use it only for return-policy questions.
  - `shopglobal-frontier` (Gemini) and `shopglobal-fast` (OpenAI): fixed models, Ollama llama3.2 on 192.168.19.42:11434 as fallback on 429/5xx/timeout.
- Policies (ai_gateway_policies, attached per model): AI Prompt Guard (profanity and attacks, HTTP 400), AI Prompt Decorator (system message: language, tools, clarifying questions), AI Semantic Cache (returns model only), AI Rate Limiting Advanced (50,000 tokens/min and 1,000,000/hour per model, Redis).
- Redis Stack (vector search) runs in namespace `shopglobal-uat-ai` (k8s/uat/redis-stack.yaml, platform pipeline), Redis databases 0 routing, 1 semantic cache, 2 token quotas. The API gateway keeps its own plain Redis on .41. The gateway config holds no password: it uses `{vault://env/redis-password}`, read by the data plane from env `REDIS_PASSWORD` (helm/uat/ai-dp.yaml, Secret `redis-auth`). Run the platform job before ai-config.
- MCP server `shopglobal-marketplace-mcp` on `/mcp/marketplace`: the 12 operations of apiops/uat/openapi.yaml as MCP tools, calling the API gateway through NGINX on the internal route `/internal/api` (service shopglobal-mock-api-internal in apiops/uat/kong.yaml): no token, allowed only from 192.168.19.42, 100 per minute.
- The AI data plane resolves `shopglobal-api-uat.pronteff.com` through the CoreDNS override k8s/uat/coredns-custom.yaml (platform pipeline).
- First run: leave DIFF_ONLY ticked (default), read the diff, then untick.
