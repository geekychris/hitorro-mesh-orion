/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh.orion;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

/**
 * Discovery + enrichment SPI for a specific cluster platform.
 *
 * <p><b>What this is NOT:</b> a lifecycle controller. Agents come online
 * by publishing heartbeats to NATS; the {@code LiveAgentRegistry} in the
 * driver is the ground truth for "who's alive." This SPI exists so the
 * driver UI can show <b>declared vs. live vs. orphan</b> — a distinction
 * the ground-truth registry can't make by itself.</p>
 *
 * <p>Two implementations planned:</p>
 * <ul>
 *   <li>{@link OrionClusterManager} — reads Orion's Service resources</li>
 *   <li>{@code KubernetesClusterManager} (future) — watches K8s Pods by label</li>
 * </ul>
 *
 * <p>If no implementation is on the classpath, the driver falls back to
 * a no-op that reports empty declared set — the mesh still works, just
 * without the enrichment.</p>
 */
public interface ClusterManager {

    /** Human-readable name of the backing platform, for UI labels ("orion", "kubernetes"). */
    String platform();

    /**
     * Agents the platform has been told about (may include ones that haven't
     * come up yet, or ones that have died silently). Compare with the live
     * registry to categorize:
     * <ul>
     *   <li>declared ∩ live      = healthy</li>
     *   <li>declared \ live      = missing (crashed or not yet started)</li>
     *   <li>live \ declared      = orphan (registered by hand? old config?)</li>
     * </ul>
     */
    Set<DeclaredAgent> declaredAgents();

    /** Optional deep-link to the platform's console for a specific agent. */
    Optional<URI> consoleUrl(String agentName);

    /**
     * Optional lifecycle op — start an agent from the driver UI. Implementations
     * that can't (or shouldn't) support this return {@code false} unchanged.
     */
    default boolean launchAgent(String agentName, String yamlSpec) { return false; }

    default boolean stopAgent(String agentName) { return false; }

    /**
     * @param name                platform-side name (Orion Service name; K8s Pod name)
     * @param declaredCapabilities capabilities the platform expects this agent to advertise
     * @param nodeName            physical or virtual node the agent runs on, or null
     */
    record DeclaredAgent(String name, Set<String> declaredCapabilities, String nodeName) {}
}
