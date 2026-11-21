/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh.orion;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser-only tests — we don't hit a live Orion here (integration lives in
 * docs/mesh-orion.md's walkthrough). These pin down the shape we expect
 * the Orion API to hand back so a schema change makes the tests fail
 * loudly instead of silently returning empty declared sets.
 */
class OrionClusterManagerTest {

    @Test
    void parseServices_reads_declared_capabilities_and_node() throws Exception {
        String json = """
                {
                  "items": [
                    {
                      "metadata": {"name": "hitorro-agent-mac02"},
                      "spec": {
                        "capabilities": ["jvssql", "partition:docs:shard-3"]
                      },
                      "status": {"nodeName": "mac02.local"}
                    },
                    {
                      "metadata": {"name": "hitorro-agent-pi04"},
                      "spec": {
                        "capabilities": ["jvssql", "partition:docs:shard-4", "arch:arm64"]
                      },
                      "status": {}
                    }
                  ]
                }
                """;
        Set<ClusterManager.DeclaredAgent> agents = OrionClusterManager.parseServices(json);
        assertThat(agents).extracting(ClusterManager.DeclaredAgent::name)
                .containsExactlyInAnyOrder("hitorro-agent-mac02", "hitorro-agent-pi04");
        assertThat(agents).anySatisfy(a -> {
            if (a.name().equals("hitorro-agent-mac02")) {
                assertThat(a.declaredCapabilities()).containsExactlyInAnyOrder(
                        "jvssql", "partition:docs:shard-3");
                assertThat(a.nodeName()).isEqualTo("mac02.local");
            }
        });
        assertThat(agents).anySatisfy(a -> {
            if (a.name().equals("hitorro-agent-pi04")) {
                assertThat(a.nodeName()).isNull();   // status.nodeName missing
            }
        });
    }

    @Test
    void parseServices_bareArrayResponse_alsoWorks() throws Exception {
        // Some Orion API versions may return the array at top level.
        String json = """
                [ {"metadata": {"name":"a"}, "spec":{"capabilities":["jvssql"]}} ]
                """;
        Set<ClusterManager.DeclaredAgent> agents = OrionClusterManager.parseServices(json);
        assertThat(agents).hasSize(1);
        assertThat(agents.iterator().next().name()).isEqualTo("a");
    }

    @Test
    void parseServices_emptyResponse_yieldsEmpty() throws Exception {
        assertThat(OrionClusterManager.parseServices("{\"items\": []}")).isEmpty();
    }

    @Test
    void parseServices_orionResourceShape_matchesLabelsAndUsesInstanceNodes() throws Exception {
        // GET /v1/resources/Service as Orion returns it: a bare array of
        // resources whose capabilities are {"name": ...} objects.
        String services = """
                [
                  {"apiVersion": "orionmesh.dev/v1", "kind": "Service",
                   "metadata": {"name": "hitorro-mesh-agent-us", "labels": {"app": "hitorro-mesh", "role": "agent"}},
                   "spec": {"capabilities": [{"name": "jvssql"}, {"name": "partition:docs:us"}]}},
                  {"apiVersion": "orionmesh.dev/v1", "kind": "Service",
                   "metadata": {"name": "hitorro-mesh-driver", "labels": {"app": "hitorro-mesh", "role": "driver"}},
                   "spec": {}},
                  {"apiVersion": "orionmesh.dev/v1", "kind": "Service",
                   "metadata": {"name": "something-else"}, "spec": {}}
                ]
                """;
        String instances = """
                [
                  {"kind": "Service", "name": "hitorro-mesh-agent-us", "node": "old-node", "exited_at": "2026-01-01T00:00:00Z"},
                  {"kind": "Service", "name": "hitorro-mesh-agent-us", "node": "mp2"},
                  {"kind": "Task", "name": "hitorro-mesh-agent-us", "node": "elsewhere"}
                ]
                """;
        Set<ClusterManager.DeclaredAgent> agents = OrionClusterManager.parseServices(
                services, OrionClusterManager.parseInstanceNodes(instances), true);
        assertThat(agents).hasSize(1);
        ClusterManager.DeclaredAgent a = agents.iterator().next();
        assertThat(a.name()).isEqualTo("hitorro-mesh-agent-us");
        assertThat(a.declaredCapabilities()).containsExactlyInAnyOrder("jvssql", "partition:docs:us");
        assertThat(a.nodeName()).isEqualTo("mp2");
    }

    @Test
    void unreachableController_yieldsEmptyDeclaredSet() {
        OrionClusterManager m = new OrionClusterManager(java.net.URI.create("http://127.0.0.1:1"),
                java.time.Duration.ofMillis(200), java.time.Duration.ofSeconds(1), "t");
        assertThat(m.platform()).isEqualTo("orion");
        assertThat(m.declaredAgents()).isEmpty();
    }

    @Test
    void noopClusterManager_isSafeDefault() {
        NoopClusterManager m = new NoopClusterManager();
        assertThat(m.platform()).isEqualTo("none");
        assertThat(m.declaredAgents()).isEmpty();
        assertThat(m.consoleUrl("anything")).isEmpty();
    }
}
