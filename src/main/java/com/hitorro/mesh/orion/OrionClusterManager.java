/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh.orion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.hitorro.util.json.JsonMappers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import com.hitorro.util.json.JsonValues;

/**
 * Reads declared mesh agents from an Orion controller's HTTP API.
 *
 * <h3>What it queries</h3>
 * <p>Orion Services labelled {@code app=hitorro-mesh, role=agent}:</p>
 * <pre>
 *   GET {orionApiBase}/v1/resources/Service     every Service resource
 *   GET {orionApiBase}/v1/instances             where each one runs
 * </pre>
 * <p>Orion's API has no label selector, so the labels are matched here. A
 * Service's {@code spec.capabilities} are {@code {"name": ...}} objects in
 * Orion's schema (plain strings are accepted too); the node comes from
 * {@code status.nodeName} or else from the Service's live instance.</p>
 *
 * <h3>Authentication</h3>
 * <p>Orion requires its cluster token as a bearer token on every call
 * ({@code orion-auth}). Pass it to the constructor, or set
 * {@code ORION_TOKEN} or {@code ORION_TOKEN_FILE} (a file holding it, e.g.
 * a mounted Secret) in the driver's environment. Note that Orion's token is
 * cluster-wide and can run workloads on every Orion node.</p>
 *
 * <h3>Design decision</h3>
 * <p>We hit the HTTP API rather than the raw JetStream KV bucket where
 * Orion stores its state, because the HTTP surface is the stable
 * contract; the KV layout is an implementation detail. Cost: two HTTP
 * roundtrips per {@link #declaredAgents()} call. The driver caches the
 * result for a few seconds — see the constructor parameter.</p>
 *
 * <h3>What this does NOT do</h3>
 * <p>Launch agents. Orion's declarative model expects you to
 * {@code orion apply -f agent.yaml} — the controller reconciles and
 * schedules; we just observe.</p>
 */
public final class OrionClusterManager implements ClusterManager {

    private static final ObjectMapper MAPPER = JsonMappers.json();
    static final String LABEL_APP = "hitorro-mesh";
    static final String LABEL_ROLE = "agent";

    private final URI orionApiBase;         // e.g. http://orion-controller:7878
    private final HttpClient http;
    private final Duration requestTimeout;
    private final String token;             // may be null (ORION_AUTH_DISABLED controllers)

    // trivial cache — declared set doesn't change often
    private volatile Set<DeclaredAgent> cache = Set.of();
    private volatile long cacheExpiresAt = 0L;
    private final long cacheTtlMillis;

    /** Token from {@code ORION_TOKEN} or {@code ORION_TOKEN_FILE}, if either is set. */
    public OrionClusterManager(URI orionApiBase, Duration requestTimeout, Duration cacheTtl) {
        this(orionApiBase, requestTimeout, cacheTtl, tokenFromEnvironment());
    }

    public OrionClusterManager(URI orionApiBase, Duration requestTimeout, Duration cacheTtl, String token) {
        this.orionApiBase = orionApiBase;
        this.requestTimeout = requestTimeout;
        this.cacheTtlMillis = cacheTtl.toMillis();
        this.token = (token == null || token.isBlank()) ? null : token.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    static String tokenFromEnvironment() {
        String t = System.getenv("ORION_TOKEN");
        if (t != null && !t.isBlank()) return t.trim();
        String file = System.getenv("ORION_TOKEN_FILE");
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file)).trim();
            } catch (IOException e) {
                return null;
            }
        }
        return null;
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
            String services = get("/v1/resources/Service");
            if (services == null) {
                // Orion unreachable / unauthorised / API drift — return the
                // last-known set. Ground truth is heartbeats, not this.
                return cache;
            }
            String instances = get("/v1/instances");
            return parseServices(services, instances == null ? Map.of() : parseInstanceNodes(instances), true);
        } catch (Exception e) {
            // Same story on network errors — never let a discovery hiccup
            // take the driver down.
            return cache;
        }
    }

    /** Body of a 200 response, or null. */
    private String get(String path) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(orionApiBase.resolve(path))
                .timeout(requestTimeout)
                .GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200 ? resp.body() : null;
    }

    /** Every Service in the response, without label matching (tests, older callers). */
    static Set<DeclaredAgent> parseServices(String json) throws Exception {
        return parseServices(json, Map.of(), false);
    }

    /**
     * @param nodes        Service name -> node of a live instance (from {@code /v1/instances})
     * @param requireLabels only Services labelled {@code app=hitorro-mesh, role=agent}
     */
    static Set<DeclaredAgent> parseServices(String json, Map<String, String> nodes, boolean requireLabels)
            throws Exception {
        JsonNode root = MAPPER.readTree(json);
        JsonNode items = root.isArray() ? root : root.get("items");
        if (items == null || !items.isArray()) return Set.of();

        Set<DeclaredAgent> out = new LinkedHashSet<>();
        for (JsonNode svc : items) {
            String name = text(svc, "metadata", "name");
            if (name == null) continue;
            if (requireLabels && !(LABEL_APP.equals(text(svc, "metadata", "labels", "app"))
                    && LABEL_ROLE.equals(text(svc, "metadata", "labels", "role")))) continue;
            JsonNode caps = path(svc, "spec", "capabilities");
            Set<String> capSet = new HashSet<>();
            if (caps != null && caps.isArray()) {
                caps.forEach(c -> {
                    if (c.isString()) capSet.add(JsonValues.asText(c));
                    else if (c.hasNonNull("name") && c.get("name").isString()) capSet.add(JsonValues.asText(c.get("name")));
                });
            }
            String node = text(svc, "status", "nodeName");
            if (node == null) node = nodes.get(name);
            out.add(new DeclaredAgent(name, capSet, node));
        }
        return out;
    }

    /** Service name -> node, from {@code /v1/instances}, skipping exited instances. */
    static Map<String, String> parseInstanceNodes(String json) throws Exception {
        Map<String, String> out = new HashMap<>();
        JsonNode root = MAPPER.readTree(json);
        if (!root.isArray()) return out;
        for (JsonNode inst : root) {
            if (!"Service".equals(text(inst, "kind"))) continue;
            if (inst.hasNonNull("exited_at")) continue;
            String name = text(inst, "name"), node = text(inst, "node");
            if (name != null && node != null) out.putIfAbsent(name, node);
        }
        return out;
    }

    private static String text(JsonNode n, String... path) {
        JsonNode cur = path(n, path);
        return (cur != null && cur.isString()) ? JsonValues.asText(cur) : null;
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
