/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh.orion;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

/**
 * Fallback {@link ClusterManager} used when no platform bridge is available.
 * Reports an empty declared set — the driver still works, just without the
 * declared-vs-live enrichment in its UI.
 */
public final class NoopClusterManager implements ClusterManager {
    @Override public String platform() { return "none"; }
    @Override public Set<DeclaredAgent> declaredAgents() { return Set.of(); }
    @Override public Optional<URI> consoleUrl(String agentName) { return Optional.empty(); }
}
