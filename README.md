# hitorro-mesh-orion

Orion bridge for the Hitorro Mesh.

Two pieces:

1. **`OrionClusterManager`** — a Java `ClusterManager` implementation the
   driver uses to read Orion's declared agent set. Enriches the driver
   UI with declared-vs-live-vs-orphan states. See
   [`ClusterManager.java`](src/main/java/com/hitorro/mesh/orion/ClusterManager.java)
   for the SPI contract.

2. **YAML templates** in [`templates/`](templates/) for the Orion Service
   resources that actually schedule the driver and agents onto your
   Orion-managed nodes.

## Design

The mesh needs zero platform code to work — agents register themselves by
publishing NATS heartbeats. This bridge is purely for enrichment. If the
Orion API is unreachable the driver keeps working from heartbeat ground
truth; the UI just loses its declared-set overlay.

## Deploy walkthrough

Assumes:
- Orion controller is up and reachable at `http://orion-controller:9080`
- `nats-server` is running (Orion likely already runs one)
- You've built the fat JARs and dropped them at `/opt/hitorro/jars/` on
  each node that will host a driver or agent

### 1. Put the config files where the JARs expect them

On each node that will host the driver:
```
/opt/hitorro/mesh/driver.yml       # analog of docker/config/driver.yml
/opt/hitorro/mesh/types/docs.json  # your JVS type definitions
```

On each node that will host an agent for shard `us`:
```
/opt/hitorro/mesh/agent-us.yml     # analog of docker/config/agent-us.yml
/opt/hitorro/mesh/data/us.ndjson   # this shard's data
```

### 2. Apply the driver Service

```bash
SHARD=us envsubst < templates/driver.yaml > /tmp/driver.yaml
orion apply -f /tmp/driver.yaml
```

Or edit `driver.yaml` in place, then `orion apply -f driver.yaml`.

### 3. Apply one agent Service per shard

```bash
for SHARD in us eu apac; do
  SHARD=$SHARD envsubst < templates/agent.yaml > /tmp/agent-$SHARD.yaml
  orion apply -f /tmp/agent-$SHARD.yaml
done
```

Orion assigns each Service to a node whose labels match `nodeSelector`.
Label your nodes ahead of time:
```bash
orion label node mac02 shard=us
orion label node linux01 shard=eu
orion label node pi04 shard=apac
```

### 4. Verify

```bash
orion get services -l app=hitorro-mesh
# should list driver + 3 agents, all Running

# From anywhere that can reach the driver:
curl -s http://<driver-node>:8085/mesh/agents | jq .
```

Should return 3 live agents. If a Service exists in Orion but doesn't
appear here, its process is dead or its NATS connection is broken — check
Orion logs for that Service.

### 5. Enable the declared-set enrichment (optional)

Wire `OrionClusterManager` into your driver's Spring context via a
`@Configuration` in a downstream module or a `@ComponentScan` extension.
Minimal wiring:

```java
@Bean
public ClusterManager clusterManager(
        @Value("${orion.api.base:http://orion-controller:9080}") URI apiBase) {
    return new OrionClusterManager(apiBase,
        Duration.ofSeconds(3),   // per-request timeout
        Duration.ofSeconds(5));  // cache TTL — declared set changes rarely
}
```

The driver UI's phase-2 "cluster view" tab reads this bean and shows the
declared/live/orphan states side by side.

## What this bridge does NOT do

- **Launch agents from the driver UI.** Orion is declarative — you
  `orion apply` and the controller reconciles. `launchAgent()` in the
  SPI is a stub with `return false;` here. If you want an interactive
  "add a new agent" button, we can POST to Orion's Service-create endpoint
  in a follow-up.
- **Take over Orion's scheduling.** Placement is decided by Orion's
  scheduler based on `nodeSelector` + capabilities in the YAML. The mesh
  just observes.
- **Manage NATS.** Assume you already have a working NATS server that all
  Orion nodes can reach. Both Orion itself and Hitorro Mesh talk to it.

## Related

- Sibling module `hitorro-mesh-k8s` provides the equivalent bridge for
  Kubernetes. Same SPI, different backend.
- Main mesh docs: [`../hitorro-mesh-core/README.md`](../hitorro-mesh-core/README.md)
  and [`../hitorro-mesh-core/ARCHITECTURE.md`](../hitorro-mesh-core/ARCHITECTURE.md).
