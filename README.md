# shopglobal-kong
Konnect hybrid platform for ShopGlobal. UAT is built first; PROD follows the same layout.
Everything is deployed by Jenkins (see Jenkinsfile). Nothing is applied by hand.

Jenkins credentials: `konnect-pat` (secret text), `github-token`.
UAT: control plane `shopglobal-uat` (US geo); namespaces `kong-uat-api`, `kong-uat-ai`.
NodePorts on the k3s node: API 30080/30443, AI 30090/30444 (NGINX forwards to these).
