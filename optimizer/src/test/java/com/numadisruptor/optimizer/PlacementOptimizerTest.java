package com.numadisruptor.optimizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.numadisruptor.topology.CpuList;
import com.numadisruptor.topology.NumaTopology;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class PlacementOptimizerTest {
    private static final double EPS = 1e-6;
    private final NumaTopology epyc = NumaTopology.epyc7513DualSocket();
    private final PlacementCostModel model = PlacementCostModel.fromTopology(epyc);

    /** producer -> ring(+cursor+gating) -> stage -> ring2 -> consumer, mirroring what the runtime builds. */
    static void addPipeline(final CommunicationGraph.Builder b, final String p, final double eps, final int payload) {
        final String ring = p + "/ring";
        final String cursor = p + "/cursor";
        final String gating = p + "/gating";
        b.vertex(CommunicationVertex.worker(p + "/producer", VertexType.PRODUCER));
        b.vertex(CommunicationVertex.memory(ring, VertexType.RING_BUFFER, 1 << 20, p + "/g"));
        b.vertex(CommunicationVertex.memory(cursor, VertexType.SEQUENCER_METADATA, 64, p + "/g"));
        b.vertex(CommunicationVertex.memory(gating, VertexType.GATING_SEQUENCE, 64, p + "/g"));
        b.vertex(CommunicationVertex.worker(p + "/stage", VertexType.STAGE));
        b.vertex(CommunicationVertex.worker(p + "/consumer", VertexType.CONSUMER));
        b.edge(p + "/producer", ring, EdgeKind.PAYLOAD_WRITE, TrafficProfile.payload(eps, payload));
        b.edge(p + "/producer", cursor, EdgeKind.OWNERSHIP_TRANSFER, TrafficProfile.sequencing(eps, 1, 1));
        b.edge(p + "/stage", ring, EdgeKind.PAYLOAD_READ, TrafficProfile.payload(eps, payload));
        b.edge(p + "/stage", cursor, EdgeKind.OWNERSHIP_TRANSFER, TrafficProfile.sequencing(eps, 8, 1));
        b.edge(p + "/stage", gating, EdgeKind.OWNERSHIP_TRANSFER, TrafficProfile.sequencing(eps, 8, 1));
        b.edge(p + "/producer", gating, EdgeKind.OWNERSHIP_TRANSFER, TrafficProfile.sequencing(eps, 64, 1));
        b.edge(p + "/stage", p + "/consumer", EdgeKind.PAYLOAD_WRITE, TrafficProfile.payload(eps, payload / 2));
        b.edge(p + "/consumer", gating, EdgeKind.OWNERSHIP_TRANSFER, TrafficProfile.sequencing(eps, 8, 1));
    }

    static CommunicationGraph pipelines(final int count) {
        final CommunicationGraph.Builder b = CommunicationGraph.builder();
        for (int i = 0; i < count; i++) {
            addPipeline(b, "p" + i, 1_000_000, 128);
        }
        return b.build();
    }

    @Test
    void costModelDistinguishesKindsAndDistance() {
        final CommunicationEdge read = new CommunicationEdge("a", "b", EdgeKind.PAYLOAD_READ, TrafficProfile.payload(1000, 64));
        final CommunicationEdge write = new CommunicationEdge("a", "b", EdgeKind.PAYLOAD_WRITE, TrafficProfile.payload(1000, 64));
        final CommunicationEdge own = new CommunicationEdge("a", "b", EdgeKind.OWNERSHIP_TRANSFER, TrafficProfile.sequencing(1000, 1, 1));

        assertEquals(0.0, model.edgeCost(read, 0, 0), 0.0, "local access costs nothing extra");
        assertTrue(model.edgeCost(read, 0, 1) < model.edgeCost(read, 0, 4), "cross-socket must cost more than same-socket");
        assertNotEquals(model.edgeCost(read, 0, 4), model.edgeCost(write, 0, 4), "reads and writes are weighted separately");
        assertNotEquals(model.edgeCost(read, 0, 4), model.edgeCost(own, 0, 4));
        // batching amortises coherence transfers 8x
        final CommunicationEdge batched = new CommunicationEdge("a", "b", EdgeKind.OWNERSHIP_TRANSFER, TrafficProfile.sequencing(1000, 8, 1));
        assertEquals(model.edgeCost(own, 0, 4) / 8, model.edgeCost(batched, 0, 4), EPS);
        // 128B payload = 2 lines
        assertEquals(2 * 1000, TrafficProfile.payload(1000, 128).payloadLinesPerSecond(), EPS);
    }

    @Test
    void calibratedLatencyMatrixOverridesDistances() {
        final double[][] ns = {{100, 150}, {150, 100}};
        final PlacementCostModel m = PlacementCostModel.fromLatencyMatrix(new int[] {0, 1}, ns, PlacementCostModel.Coefficients.defaults());
        assertEquals(0.5, m.excessDistance(0, 1), EPS);
        assertEquals(0.0, m.excessDistance(1, 1), EPS);
    }

    @Test
    void singlePipelineStaysOnOneNode() {
        final CommunicationGraph g = pipelines(1);
        for (final PlacementOptimizer o : List.of(new GreedyPlacementOptimizer(), new LocalSearchOptimizer(new GreedyPlacementOptimizer()))) {
            final PlacementPlan plan = o.place(g, epyc, model);
            assertEquals(0.0, plan.modelCost(), EPS, o.name());
            assertEquals(1, new HashSet<>(plan.nodes().values()).size(), o.name());
            assertTrue(plan.validate(g, epyc, PlacementConstraints.defaults()).isEmpty());
        }
    }

    @Test
    void greedyKeepsIndependentStreamsTogetherWhereNaiveSplitsThem() {
        // 8 pipelines x 3 workers = 24 workers; each node has 8 cpus, so a first-fit fill splits the third pipeline
        final CommunicationGraph g = pipelines(8);
        final PlacementPlan naive = new NaivePlacementOptimizer().place(g, epyc, model);
        final PlacementPlan rr = new RoundRobinPlacementOptimizer().place(g, epyc, model);
        final PlacementPlan greedy = new GreedyPlacementOptimizer().place(g, epyc, model);
        final PlacementPlan refined = new LocalSearchOptimizer(new NaivePlacementOptimizer()).place(g, epyc, model);

        assertEquals(0.0, greedy.modelCost(), EPS, "every pipeline fits one node, so a good plan has no remote traffic");
        assertTrue(naive.modelCost() > 0, "first-fit should have split at least one pipeline");
        assertTrue(rr.modelCost() > greedy.modelCost());
        assertTrue(refined.modelCost() <= naive.modelCost() + EPS, "local search never worsens its starting plan");
        for (final PlacementPlan plan : List.of(naive, rr, greedy, refined)) {
            assertTrue(plan.validate(g, epyc, PlacementConstraints.defaults()).isEmpty(), plan.optimizerName());
        }
    }

    @Test
    void pipelineLargerThanANodeSplitsOnSameSocketBeforeCrossingSockets() {
        // one stream with 12 workers (> 8 cpus per node), all talking to one ring
        final CommunicationGraph.Builder b = CommunicationGraph.builder();
        b.vertex(CommunicationVertex.memory("ring", VertexType.RING_BUFFER, 1 << 20, "g"));
        b.vertex(CommunicationVertex.memory("cursor", VertexType.SEQUENCER_METADATA, 64, "g"));
        b.vertex(CommunicationVertex.worker("producer", VertexType.PRODUCER));
        b.edge("producer", "ring", EdgeKind.PAYLOAD_WRITE, TrafficProfile.payload(1e6, 64));
        b.edge("producer", "cursor", EdgeKind.OWNERSHIP_TRANSFER, TrafficProfile.sequencing(1e6, 1, 1));
        for (int i = 0; i < 11; i++) {
            final String c = String.format("consumer%02d", i);
            b.vertex(CommunicationVertex.worker(c, VertexType.CONSUMER));
            b.edge(c, "ring", EdgeKind.PAYLOAD_READ, TrafficProfile.payload(1e6, 64));
            b.edge(c, "cursor", EdgeKind.OWNERSHIP_TRANSFER, TrafficProfile.sequencing(1e6, 4, 1));
        }
        final CommunicationGraph g = b.build();
        final PlacementPlan plan = new LocalSearchOptimizer(new GreedyPlacementOptimizer()).place(g, epyc, model);

        final Set<Integer> sockets = new HashSet<>();
        plan.nodes().values().forEach(n -> sockets.add(epyc.node(n).socketId()));
        assertEquals(1, sockets.size(), "12 workers fit in one socket, so nothing should cross the socket boundary");
        assertTrue(plan.validate(g, epyc, PlacementConstraints.defaults()).isEmpty());
    }

    @Test
    void heuristicsMatchOrBeatNothingBetterThanBruteForceAndRarelyMissIt() {
        // 3 nodes x 2 cpus (1 socket, then 1 remote socket): tiny enough to enumerate
        final NumaTopology tiny = NumaTopology.synthetic(2, 2, 1, 2, 12, 32).restrictedTo(CpuList.parse("0-7"));
        final PlacementCostModel m = PlacementCostModel.fromTopology(tiny);
        int optimalHits = 0;
        final int trials = 300;
        for (int seed = 0; seed < trials; seed++) {
            final Random rnd = new Random(seed);
            final CommunicationGraph.Builder b = CommunicationGraph.builder();
            final int workers = 4 + rnd.nextInt(3); // 4..6 workers; capacity is 8
            for (int i = 0; i < workers; i++) {
                b.vertex(CommunicationVertex.worker("w" + i, i == 0 ? VertexType.PRODUCER : VertexType.CONSUMER));
            }
            for (int i = 0; i < workers; i++) {
                for (int j = 0; j < workers; j++) {
                    if (i != j && rnd.nextInt(3) == 0) {
                        b.edge("w" + i, "w" + j, EdgeKind.values()[rnd.nextInt(3)],
                            new TrafficProfile(1000 + rnd.nextInt(100_000), 64 * (1 + rnd.nextInt(4)), 1 + rnd.nextInt(8), 1));
                    }
                }
            }
            final CommunicationGraph g = b.build();
            final PlacementPlan opt = new BruteForcePlacementOptimizer().place(g, tiny, m);
            final PlacementPlan greedy = new GreedyPlacementOptimizer().place(g, tiny, m);
            final PlacementPlan refined = new LocalSearchOptimizer(new GreedyPlacementOptimizer()).place(g, tiny, m);

            assertTrue(opt.modelCost() <= greedy.modelCost() + EPS, "seed " + seed + ": brute force must be a lower bound");
            assertTrue(refined.modelCost() <= greedy.modelCost() + EPS, "seed " + seed + ": local search must not worsen greedy");
            assertTrue(opt.modelCost() <= refined.modelCost() + EPS, "seed " + seed);
            assertTrue(refined.validate(g, tiny, PlacementConstraints.defaults()).isEmpty());
            if (refined.modelCost() <= opt.modelCost() * (1 + 1e-9) + EPS) {
                optimalHits++;
            }
        }
        System.out.println("greedy+local-search optimal in " + optimalHits + "/" + trials + " random tiny instances");
        // A heuristic may miss the optimum; a collapse in the hit rate signals a regression.
        assertTrue(optimalHits >= trials * 0.85, "greedy+local search optimal in only " + optimalHits + "/" + trials);
    }

    @Test
    void outputIsDeterministicAndIndependentOfInsertionOrder() {
        final CommunicationGraph forward = pipelines(5);
        final CommunicationGraph.Builder rev = CommunicationGraph.builder();
        final List<CommunicationVertex> vs = new ArrayList<>(forward.vertices().values());
        Collections.reverse(vs);
        vs.forEach(rev::vertex);
        final List<CommunicationEdge> es = new ArrayList<>(forward.edges());
        Collections.reverse(es);
        es.forEach(e -> rev.edge(e.from(), e.to(), e.kind(), e.traffic()));

        final PlacementOptimizer o = new LocalSearchOptimizer(new GreedyPlacementOptimizer());
        final PlacementPlan a = o.place(forward, epyc, model);
        final PlacementPlan b = o.place(forward, epyc, model);
        final PlacementPlan c = o.place(rev.build(), epyc, model);
        assertEquals(a.nodes(), b.nodes());
        assertEquals(a.cpus(), b.cpus());
        assertEquals(a.nodes(), c.nodes());
        assertEquals(a.cpus(), c.cpus());
    }

    @Test
    void respectsRestrictedCpuAndMemoryMasks() {
        // job only owns node 1 (cpus 8-15) and node 5 (cpus 40-47)
        final NumaTopology job = epyc.restrictedTo(CpuList.parse("8-15,40-47"));
        final CommunicationGraph g = pipelines(2);
        final PlacementPlan plan = new GreedyPlacementOptimizer().place(g, job, PlacementCostModel.fromTopology(job));
        for (final int cpu : plan.cpus().values()) {
            assertTrue(job.allowedCpus().get(cpu), "cpu " + cpu + " is outside the job mask");
        }
        for (final int node : plan.nodes().values()) {
            assertTrue(node == 1 || node == 5, "node " + node);
        }
    }

    @Test
    void reservedCpusAreNeverAssigned() {
        final java.util.BitSet reserved = CpuList.parse("0-3");
        final PlacementConstraints c = new PlacementConstraints(true, reserved);
        final PlacementPlan plan = new GreedyPlacementOptimizer().place(pipelines(1), epyc.restrictedTo(CpuList.parse("0-7")), model, c);
        plan.cpus().values().forEach(cpu -> assertFalse(reserved.get(cpu)));
    }

    @Test
    void workersTakeDistinctCoresBeforeSmtSiblings() {
        final CommunicationGraph g = pipelines(1); // 3 workers, node 0 has 4 cores x 2 threads
        final PlacementPlan plan = new GreedyPlacementOptimizer().place(g, epyc, model);
        final Set<Long> cores = new HashSet<>();
        plan.cpus().values().forEach(cpu -> cores.add(epyc.cpu(cpu).physicalCoreKey()));
        assertEquals(3, cores.size());
        assertTrue(plan.warnings().isEmpty());
    }

    @Test
    void smtSharingWarnsOrIsForbiddenByConstraint() {
        // 6 workers on a 1-node, 4-core, 8-thread job
        final NumaTopology oneNode = epyc.restrictedTo(CpuList.parse("0-7"));
        final CommunicationGraph g = pipelines(2);
        final PlacementPlan shared = new GreedyPlacementOptimizer().place(g, oneNode, model);
        assertFalse(shared.warnings().isEmpty(), "6 workers on 4 cores must warn about SMT sharing");

        assertThrows(InfeasiblePlacementException.class,
            () -> new GreedyPlacementOptimizer().place(g, oneNode, model, PlacementConstraints.noSmtSharing()));
    }

    @Test
    void infeasibleWorkloadsAreReportedNotMisplaced() {
        final NumaTopology tinyJob = epyc.restrictedTo(CpuList.parse("0-1"));
        for (final PlacementOptimizer o : List.of(new NaivePlacementOptimizer(), new RoundRobinPlacementOptimizer(),
            new GreedyPlacementOptimizer(), new LocalSearchOptimizer(new GreedyPlacementOptimizer()), new BruteForcePlacementOptimizer())) {
            assertThrows(InfeasiblePlacementException.class, () -> o.place(pipelines(1), tinyJob, model), o.name());
        }
    }

    @Test
    void allowedNodeConstraintIsHonouredOrRejected() {
        final CommunicationGraph.Builder b = CommunicationGraph.builder();
        b.vertex(CommunicationVertex.worker("pinned", VertexType.PRODUCER).withAllowedNodes(Set.of(6)));
        b.vertex(CommunicationVertex.worker("other", VertexType.CONSUMER));
        b.edge("pinned", "other", EdgeKind.PAYLOAD_WRITE, TrafficProfile.payload(1e6, 64));
        final PlacementPlan plan = new LocalSearchOptimizer(new GreedyPlacementOptimizer()).place(b.build(), epyc, model);
        assertEquals(6, plan.nodeOf("pinned"));
        assertEquals(6, plan.nodeOf("other"), "the peer should join the pinned worker");

        final CommunicationGraph.Builder bad = CommunicationGraph.builder();
        bad.vertex(CommunicationVertex.worker("x", VertexType.PRODUCER).withAllowedNodes(Set.of(99)));
        assertThrows(InfeasiblePlacementException.class, () -> new GreedyPlacementOptimizer().place(bad.build(), epyc, model));
    }

    @Test
    void memoryCapacityForcesSpill() {
        final NumaTopology t = NumaTopology.synthetic(1, 2, 2, 2, 12, 32); // 2 nodes, 64 GiB each
        final CommunicationGraph.Builder b = CommunicationGraph.builder();
        b.vertex(CommunicationVertex.memory("ringA", VertexType.RING_BUFFER, 40L << 30, "a"));
        b.vertex(CommunicationVertex.memory("ringB", VertexType.RING_BUFFER, 40L << 30, "b"));
        b.vertex(CommunicationVertex.worker("w", VertexType.PRODUCER));
        b.edge("w", "ringA", EdgeKind.PAYLOAD_WRITE, TrafficProfile.payload(1e6, 64));
        b.edge("w", "ringB", EdgeKind.PAYLOAD_WRITE, TrafficProfile.payload(1e6, 64));
        final PlacementPlan plan = new LocalSearchOptimizer(new GreedyPlacementOptimizer()).place(b.build(), t, PlacementCostModel.fromTopology(t));
        assertNotEquals(plan.nodeOf("ringA"), plan.nodeOf("ringB"), "80 GiB cannot fit on one 64 GiB node");
        assertTrue(plan.validate(b.build(), t, PlacementConstraints.defaults()).isEmpty());
    }

    @Test
    void validatorCatchesIllegalPlans() {
        final CommunicationGraph g = pipelines(1);
        final PlacementPlan good = new GreedyPlacementOptimizer().place(g, epyc, model);
        final var nodes = new java.util.HashMap<>(good.nodes());
        final var cpus = new java.util.HashMap<>(good.cpus());

        // worker on a cpu that belongs to a different node
        cpus.put("p0/producer", 63);
        assertFalse(new PlacementPlan(nodes, cpus, 0, "bad", List.of()).validate(g, epyc, PlacementConstraints.defaults()).isEmpty());

        // two workers on one cpu
        final var dup = new java.util.HashMap<>(good.cpus());
        dup.put("p0/stage", dup.get("p0/consumer"));
        assertFalse(new PlacementPlan(nodes, dup, 0, "bad", List.of()).validate(g, epyc, PlacementConstraints.defaults()).isEmpty());

        // colocation group split
        final var split = new java.util.HashMap<>(good.nodes());
        split.put("p0/ring", 5);
        assertFalse(new PlacementPlan(split, good.cpus(), 0, "bad", List.of()).validate(g, epyc, PlacementConstraints.defaults()).isEmpty());

        // cpu not allowed for the job
        final NumaTopology job = epyc.restrictedTo(CpuList.parse("0-1"));
        assertFalse(good.validate(g, job, PlacementConstraints.defaults()).isEmpty());
    }

    @Test
    void graphRejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> CommunicationGraph.builder()
            .vertex(CommunicationVertex.worker("a", VertexType.PRODUCER)).edge("a", "ghost", EdgeKind.PAYLOAD_WRITE, TrafficProfile.payload(1, 1)).build());
        assertThrows(IllegalArgumentException.class, () -> CommunicationGraph.builder()
            .vertex(CommunicationVertex.worker("a", VertexType.PRODUCER)).vertex(CommunicationVertex.worker("a", VertexType.CONSUMER)));
        assertThrows(IllegalArgumentException.class, () -> new CommunicationEdge("a", "a", EdgeKind.PAYLOAD_READ, TrafficProfile.payload(1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new CommunicationVertex("r", VertexType.RING_BUFFER, 1, 0, Set.of(), null));
    }

    @Test
    void componentsGroupIndependentStreams() {
        assertEquals(4, pipelines(4).components().size());
    }

    @Test
    void largeGraphPlacesQuicklyAndLegally() {
        // 16 pipelines (48 workers) on 64 cpus; guards against accidental exponential behaviour
        final CommunicationGraph g = pipelines(16);
        final long t0 = System.nanoTime();
        final PlacementPlan plan = new LocalSearchOptimizer(new GreedyPlacementOptimizer()).place(g, epyc, model);
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(plan.validate(g, epyc, PlacementConstraints.defaults()).isEmpty());
        assertEquals(0.0, plan.modelCost(), EPS);
        assertTrue(ms < 5_000, "took " + ms + " ms");
    }
}
