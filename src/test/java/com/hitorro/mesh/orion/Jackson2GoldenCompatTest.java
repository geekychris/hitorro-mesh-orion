/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh.orion;

import java.util.*;

/**
 * Pins {@link OrionClusterManager#parseServices} (what the driver makes of the Orion controller's
 * {@code /v1/services} response) to 3.0.2 on Jackson 2: 27 responses, well formed, wrongly typed
 * names/capabilities/nodes, duplicates, trailing content, empty and malformed.
 * {@code jackson2-golden.txt} was produced by running the same program against the 3.0.2 classes on
 * Jackson 2.18.
 */
class Jackson2GoldenCompatTest {
  static String svc(String name, String caps, String node) {
    return "{\"metadata\":{\"name\":" + name + "},\"spec\":{\"capabilities\":" + caps + "},\"status\":{\"nodeName\":" + node + "}}";
  }
  static final String[] RESPONSES = {
    "{\"items\":[" + svc("\"a1\"", "[\"sql\",\"lucene\"]", "\"n1\"") + "," + svc("\"a2\"", "[]", "null") + "]}",
    "[" + svc("\"b1\"", "[\"sql\"]", "\"n2\"") + "]",
    "{\"items\":[]}", "{}", "[]", "{\"items\":{}}", "{\"items\":null}", "{\"items\":\"x\"}",
    "{\"items\":[" + svc("7", "[\"sql\"]", "\"n\"") + "," + svc("null", "[]", "\"n\"") + "," + svc("{\"x\":1}", "[]", "\"n\"") + "]}",
    "{\"items\":[" + svc("\"c1\"", "[\"sql\",7,null,true,{\"x\":1},[\"y\"],\"\",\"sql\"]", "7") + "]}",
    "{\"items\":[" + svc("\"c2\"", "\"sql\"", "{\"n\":1}") + "," + svc("\"c3\"", "{\"0\":\"sql\"}", "[\"n\"]") + "]}",
    "{\"items\":[{\"metadata\":{\"name\":\"d1\"}},{\"metadata\":{}},{},7,\"s\",null,[]]}",
    "{\"items\":[" + svc("\"dup\"", "[\"a\"]", "\"n1\"") + "," + svc("\"dup\"", "[\"a\"]", "\"n1\"") + "," + svc("\"dup\"", "[\"b\"]", "\"n1\"") + "]}",
    "{\"items\":[" + svc("\"\u00e9\ud83d\ude00\"", "[\"\\u0000\"]", "\"\"") + "]}",
    "{\"items\":[" + svc("\"t\"", "[\"sql\"]", "\"n\"") + "]} trailing",
    "{\"items\":[" + svc("\"t2\"", "[\"sql\"]", "\"n\"") + "]}{\"items\":[]}",
    "{\"items\":[], \"items\":[" + svc("\"last\"", "[]", "\"n\"") + "]}",
    "", "   ", "null", "7", "\"s\"", "{\"items\":[", "{items:[]}", "{'items':[]}", "{\"items\":[],}", "<html>error</html>"
  };

  static String generate() {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < RESPONSES.length; i++) {
      String v;
      try {
        List<String> agents = new ArrayList<>();
        for (ClusterManager.DeclaredAgent a : OrionClusterManager.parseServices(RESPONSES[i]))
          agents.add(a.name() + "|" + new TreeSet<>(a.declaredCapabilities()) + "|" + a.nodeName());
        v = String.join(" ; ", agents);
      } catch (Throwable t) {
        v = "!error";
      }
      out.append("parseServices.").append(i).append('=').append(v.replace("\u0000", "\\0")).append('\n');
    }
    return out.toString();
  }

  @org.junit.jupiter.api.Test
  void matchesJackson2() throws Exception {
    java.util.Map<String, String> golden = new java.util.LinkedHashMap<>();
    try (java.io.InputStream in = getClass().getResourceAsStream("jackson2-golden.txt")) {
      for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
        int eq = line.indexOf('=');
        if (eq > 0) golden.put(line.substring(0, eq), line.substring(eq + 1));
      }
    }
    java.util.Map<String, String> actual = new java.util.LinkedHashMap<>();
    for (String line : generate().split("\n")) {
      int eq = line.indexOf('=');
      if (eq > 0) actual.put(line.substring(0, eq), line.substring(eq + 1));
    }
    java.util.List<String> diffs = new java.util.ArrayList<>();
    for (String k : golden.keySet())
      if (!java.util.Objects.equals(golden.get(k), actual.get(k))) diffs.add(k + ": jackson2=" + golden.get(k) + " now=" + actual.get(k));
    for (String k : actual.keySet()) if (!golden.containsKey(k)) diffs.add(k + " (not in golden)");
    org.junit.jupiter.api.Assertions.assertTrue(diffs.isEmpty(), diffs.size() + " differences:\n" + String.join("\n", diffs));
    org.junit.jupiter.api.Assertions.assertEquals(27, golden.size());
  }
}
