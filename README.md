# shopglobal-kong
Konnect hybrid platform for ShopGlobal. UAT is built first; PROD follows the same layout.
Everything is deployed by Jenkins (see Jenkinsfile). Nothing is applied by hand.

Jenkins host: 192.168.19.42. Credentials: `konnect-pat` (secret text), `github-token`.
Kubeconfigs on the Jenkins host: `/var/lib/jenkins/.kube/config` (.42, AI) and `config-api` (.41, API).

UAT: control plane `shopglobal-uat` (IN geo, https://in.api.konghq.com)
- API data plane: k3s on 192.168.19.41, namespace `shopglobal-uat-api`, NodePort 30080/30443
- AI data plane:  k3s on 192.168.19.42, namespace `shopglobal-uat-ai`,  NodePort 30090/30444
