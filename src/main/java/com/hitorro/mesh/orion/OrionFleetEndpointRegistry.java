/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.mesh.orion;

import com.hitorro.mesh.driver.fleet.FleetEndpointRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovers {@code hitorro-fleet-retrieval} instances registered in
 * Orion. Uses {@link OrionClusterManager}'s declared-agents view —
 * fleet-retrieval instances declare their name / capability the same
 * way agents do.
 *
 * <p>Cached with a short TTL so per-request calls don't hammer the
 * Orion API. Refresh is lazy on {@link #endpoints()}.</p>
 */
public final class OrionFleetEndpointRegistry implements FleetEndpointRegistry {

    private static final Logger log = LoggerFactory.getLogger(OrionFleetEndpointRegistry.class);
    /** Capability that fleet-retrieval instances declare in their Orion spec. */
    private static final String  FLEET_CAPABILITY = "fleet-retrieval";
    private static final int     DEFAULT_PORT     = 8087;
    private static final long    DEFAULT_TTL_MS   = 5_000L;

    private final OrionClusterManager orion;
    private final int defaultPort;
    private final long ttlMs;

    private volatile List<String> cachedEndpoints = List.of();
    private volatile long cachedAtMs = 0L;

    public OrionFleetEndpointRegistry(OrionClusterManager orion) {
        this(orion, DEFAULT_PORT, DEFAULT_TTL_MS);
    }

    public OrionFleetEndpointRegistry(OrionClusterManager orion, int defaultPort, long ttlMs) {
        this.orion = orion;
        this.defaultPort = defaultPort;
        this.ttlMs = ttlMs;
    }

    @Override public String platform() { return "orion"; }

    @Override
    public List<String> endpoints() {
        long now = System.currentTimeMillis();
        if (now - cachedAtMs < ttlMs) return cachedEndpoints;
        try {
            List<String> discovered = new ArrayList<>();
            for (var declared : orion.declaredAgents()) {
                if (declared.declaredCapabilities() == null
                        || !declared.declaredCapabilities().contains(FLEET_CAPABILITY)) continue;
                String host = declared.nodeName() != null ? declared.nodeName() : declared.name();
                discovered.add("http://" + host + ":" + defaultPort);
            }
            cachedEndpoints = List.copyOf(discovered);
            cachedAtMs = now;
            return cachedEndpoints;
        } catch (Exception e) {
            log.warn("orion fleet discovery failed: {} — returning cached ({} endpoints)",
                    e.getMessage(), cachedEndpoints.size());
            return cachedEndpoints;
        }
    }
}
