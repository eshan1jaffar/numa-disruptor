package com.numadisruptor.benchmarks.jmh;

import com.numadisruptor.optimizer.CommunicationGraph;
import com.numadisruptor.optimizer.GreedyPlacementOptimizer;
import com.numadisruptor.optimizer.LocalSearchOptimizer;
import com.numadisruptor.optimizer.PlacementCostModel;
import com.numadisruptor.optimizer.PlacementOptimizer;
import com.numadisruptor.runtime.ConsumptionMode;
import com.numadisruptor.runtime.WorkloadGraphBuilder;
import com.numadisruptor.runtime.WorkloadSpec;
import com.numadisruptor.topology.NumaTopology;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Placement calculation overhead as a function of the number of partitions, on the 64-CPU EPYC layout. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
public class PlacementOptimizerBenchmark {
    @Param({"4", "8", "16"})
    public int partitions;

    private NumaTopology topology;
    private PlacementCostModel model;
    private CommunicationGraph graph;
    private final PlacementOptimizer greedy = new GreedyPlacementOptimizer();
    private final PlacementOptimizer refined = new LocalSearchOptimizer(new GreedyPlacementOptimizer());

    @Setup
    public void setup() {
        topology = NumaTopology.epyc7513DualSocket();
        model = PlacementCostModel.fromTopology(topology);
        graph = WorkloadGraphBuilder.build(new WorkloadSpec(
            partitions, 1, 1, 1, ConsumptionMode.MULTICAST, 1 << 14, false, 1_000_000, 64, 8));
    }

    @Benchmark
    public Object greedyOnly() {
        return greedy.place(graph, topology, model);
    }

    @Benchmark
    public Object greedyPlusLocalSearch() {
        return refined.place(graph, topology, model);
    }
}
