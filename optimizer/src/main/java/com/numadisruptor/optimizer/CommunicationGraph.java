package com.numadisruptor.optimizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable graph of placeable components and the traffic between them. Build with {@link #builder()}.
 *
 * <p>Iteration order of every accessor is by vertex id, so optimizers fed the same graph behave identically
 * regardless of insertion order.
 */
public final class CommunicationGraph {
    private final Map<String, CommunicationVertex> vertices;
    private final List<CommunicationEdge> edges;

    private CommunicationGraph(final Map<String, CommunicationVertex> vertices, final List<CommunicationEdge> edges) {
        this.vertices = Collections.unmodifiableMap(new TreeMap<>(vertices));
        final List<CommunicationEdge> sorted = new ArrayList<>(edges);
        sorted.sort((a, b) -> {
            int c = a.from().compareTo(b.from());
            if (c == 0) {
                c = a.to().compareTo(b.to());
            }
            return c != 0 ? c : a.kind().compareTo(b.kind());
        });
        this.edges = List.copyOf(sorted);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, CommunicationVertex> vertices() { return vertices; }

    public CommunicationVertex vertex(final String id) {
        final CommunicationVertex v = vertices.get(id);
        if (v == null) {
            throw new IllegalArgumentException("unknown vertex " + id);
        }
        return v;
    }

    public List<CommunicationEdge> edges() { return edges; }

    /** Connected components (ignoring edge direction) as sorted vertex-id sets, ordered by smallest id. */
    public List<Set<String>> components() {
        final Map<String, String> parent = new HashMap<>();
        vertices.keySet().forEach(v -> parent.put(v, v));
        edges.forEach(e -> union(parent, e.from(), e.to()));
        vertices.values().forEach(v -> {
            if (v.colocationGroup() != null) {
                // vertices of one colocation group are one unit even if no edge connects them
                vertices.values().stream().filter(o -> v.colocationGroup().equals(o.colocationGroup()))
                    .forEach(o -> union(parent, v.id(), o.id()));
            }
        });
        final Map<String, Set<String>> byRoot = new TreeMap<>();
        for (final String v : vertices.keySet()) {
            byRoot.computeIfAbsent(find(parent, v), k -> new TreeSet<>()).add(v);
        }
        final List<Set<String>> out = new ArrayList<>(byRoot.values());
        out.sort((a, b) -> ((TreeSet<String>) a).first().compareTo(((TreeSet<String>) b).first()));
        return out;
    }

    private static String find(final Map<String, String> parent, final String x) {
        String r = x;
        while (!parent.get(r).equals(r)) {
            r = parent.get(r);
        }
        String c = x;
        while (!parent.get(c).equals(r)) {
            final String next = parent.get(c);
            parent.put(c, r);
            c = next;
        }
        return r;
    }

    private static void union(final Map<String, String> parent, final String a, final String b) {
        final String ra = find(parent, a);
        final String rb = find(parent, b);
        if (!ra.equals(rb)) {
            // deterministic: smaller id becomes the root
            if (ra.compareTo(rb) < 0) {
                parent.put(rb, ra);
            } else {
                parent.put(ra, rb);
            }
        }
    }

    public int totalCpuDemand() {
        return vertices.values().stream().mapToInt(CommunicationVertex::cpuDemand).sum();
    }

    public static final class Builder {
        private final Map<String, CommunicationVertex> vertices = new LinkedHashMap<>();
        private final List<CommunicationEdge> edges = new ArrayList<>();

        public Builder vertex(final CommunicationVertex v) {
            if (vertices.putIfAbsent(v.id(), v) != null) {
                throw new IllegalArgumentException("duplicate vertex " + v.id());
            }
            return this;
        }

        public Builder edge(final String from, final String to, final EdgeKind kind, final TrafficProfile traffic) {
            edges.add(new CommunicationEdge(from, to, kind, traffic));
            return this;
        }

        public CommunicationGraph build() {
            for (final CommunicationEdge e : edges) {
                if (!vertices.containsKey(e.from()) || !vertices.containsKey(e.to())) {
                    throw new IllegalArgumentException("edge references unknown vertex: " + e);
                }
            }
            return new CommunicationGraph(vertices, edges);
        }
    }
}
