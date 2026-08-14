/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh.orion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Reads declared mesh agents from an Orion controller's HTTP API.
 *
 * <h3>What it queries</h3>
 * <p>Orion Services labelled {@code app=hitorro-mesh, role=agent}.
 * Expected controller endpoint (subject to Orion's API surface):</p>
 * <pre>
 *   GET {orionApiBase}/v1/services?labelSelector=app=hitorro-mesh,role=agent
 * </pre>
 * <p>The response is expected to be a JSON array of Service objects with
 * {@code metadata.name}, {@code spec.capabilities} (array of strings),
 * {@code status.nodeName} (assigned node, may be missing).</p>
 *
 * <h3>Design decision</h3>
 * <p>We hit the HTTP API rather than the raw JetStream KV bucket where
 * Orion stores its state, because the HTTP surface is the stable
 * contract; the KV layout is an implementation detail. Cost: one HTTP
 * roundtrip per {@link #declaredAgents()} call. The driver caches the
 * result for a few seconds — see the constructor parameter.</p>
 *
 * <h3>What this does NOT do</h3>
 * <p>Launch agents. Orion's declarative model expects you to
 * {@code orion apply -f agent.yaml} — the controller reconciles and
 * schedules; we just observe. If the driver UI ever grows a "launch new
 * agent" button, {@link #launchAgent} can POST to Orion's Service-create
 * endpoint. Deliberately left as a stub.</p>
 */
public final class OrionClusterManager implements ClusterManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI orionApiBase;         // e.g. http://orion-controller:9080
    private final HttpClient http;
    private final Duration requestTimeout;

    // trivial cache — declared set doesn't change often
    private volatile Set<DeclaredAgent> cache = Set.of();
    private volatile long cacheExpiresAt = 0L;
    private final long cacheTtlMillis;

    public OrionClusterManager(URI orionApiBase, Duration requestTimeout, Duration cacheTtl) {
        this.orionApiBase = orionApiBase;
        this.requestTimeout = requestTimeout;
        this.cacheTtlMillis = cacheTtl.toMillis();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override public String platform() { return "orion"; }

    @Override
    public Set<DeclaredAgent> declaredAgents() {
        long now = System.currentTimeMillis();
        if (now < cacheExpiresAt) return cache;
        Set<DeclaredAgent> fresh = fetch();
        cache = fresh;
        cacheExpiresAt = now + cacheTtlMillis;
        return fresh;
    }

    @Override
    public Optional<URI> consoleUrl(String agentName) {
        // Convention: Orion's UI lives on the same host as the controller API.
        return Optional.of(orionApiBase.resolve("/services/" + agentName));
    }

    private Set<DeclaredAgent> fetch() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(orionApiBase.resolve("/v1/services?labelSelector=app=hitorro-mesh,role=agent"))
                    .timeout(requestTimeout)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                // Orion unreachable / label selector wrong / API drift — return
                // the last-known set. Driver UI shows a stale warning if the
                // cache is older than the TTL and we fell through to this path.
                return cache;
            }
            return parseServices(resp.body());
        } catch (Exception e) {
            // Same story on network errors — never let a discovery hiccup
            // take the driver down. Ground truth is heartbeats, not this.
            return cache;
        }
    }

    static Set<DeclaredAgent> parseServices(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        JsonNode items = root.isArray() ? root : root.get("items");
        if (items == null || !items.isArray()) return Set.of();

        Set<DeclaredAgent> out = new LinkedHashSet<>();
        for (JsonNode svc : items) {
            String name = text(svc, "metadata", "name");
            if (name == null) continue;
            JsonNode caps = path(svc, "spec", "capabilities");
            Set<String> capSet = new HashSet<>();
            if (caps != null && caps.isArray()) {
                caps.forEach(c -> { if (c.isTextual()) capSet.add(c.asText()); });
            }
            String node = text(svc, "status", "nodeName");
            out.add(new DeclaredAgent(name, capSet, node));
        }
        return out;
    }

    private static String text(JsonNode n, String... path) {
        JsonNode cur = path(n, path);
        return (cur != null && cur.isTextual()) ? cur.asText() : null;
    }

    private static JsonNode path(JsonNode n, String... path) {
        JsonNode cur = n;
        for (String p : path) {
            if (cur == null) return null;
            cur = cur.get(p);
        }
        return cur;
    }
}
