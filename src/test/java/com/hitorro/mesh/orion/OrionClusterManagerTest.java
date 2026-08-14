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
    void noopClusterManager_isSafeDefault() {
        NoopClusterManager m = new NoopClusterManager();
        assertThat(m.platform()).isEqualTo("none");
        assertThat(m.declaredAgents()).isEmpty();
        assertThat(m.consoleUrl("anything")).isEmpty();
    }
}
