package com.numadisruptor.benchmarks.calibrate;

import com.numadisruptor.affinity.CpuAffinity;
import com.numadisruptor.benchmarks.MiniJson;
import com.numadisruptor.memory.NumaAllocator;
import com.numadisruptor.memory.NumaUnavailableException;
import com.numadisruptor.topology.TopologyDiscovery;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Measures node-to-node latency and cache-line ping-pong and writes them as JSON for {@link CalibratedCostModel}.
 * Exit codes: 0 ok, 1 bad arguments or I/O error, 2 environment cannot measure (no libnuma, no thread pinning).
 */
public final class CalibrationMain {
    private CalibrationMain() { }

    public static void main(final String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(final String[] args, final PrintStream out, final PrintStream err) {
        Path file = Path.of("latency.json");
        long sizeMib = 256;
        int reps = Calibrator.Params.defaults().reps();
        long loads = Calibrator.Params.defaults().loadsPerRep();
        int rounds = Calibrator.Params.defaults().pingPongRounds();
        long seed = Calibrator.Params.defaults().seed();
        final Calibrator.Params params;
        try {
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + args[i]);
                }
                final String v = args[i + 1];
                switch (args[i]) {
                    case "--out" -> file = Path.of(v);
                    case "--size-mib" -> sizeMib = Long.parseLong(v);
                    case "--reps" -> reps = Integer.parseInt(v);
                    case "--loads" -> loads = Long.parseLong(v);
                    case "--pingpong-rounds" -> rounds = Integer.parseInt(v);
                    case "--seed" -> seed = Long.parseLong(v);
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            params = new Calibrator.Params(sizeMib << 20, reps, loads, rounds, seed);
        } catch (IllegalArgumentException e) {
            err.println("calibrate: " + e.getMessage());
            err.println("usage: CalibrationMain [--out latency.json] [--size-mib 256] [--reps 7] [--loads 2000000] "
                + "[--pingpong-rounds 200000] [--seed 42]");
            return 1;
        }

        final NumaAllocator allocator;
        try {
            allocator = NumaAllocator.open();
        } catch (NumaUnavailableException e) {
            err.println("calibrate: cannot measure, libnuma is unavailable (" + e.getMessage() + "). Install libnuma and run on Linux.");
            return 2;
        }
        final CpuAffinity affinity = CpuAffinity.system();
        if (!affinity.isSupported()) {
            err.println("calibrate: cannot measure, thread pinning is unsupported on this host.");
            return 2;
        }
        final Map<String, Object> result = Calibrator.run(TopologyDiscovery.discover(), affinity, allocator, params);
        try {
            Files.writeString(file, MiniJson.write(result), StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("calibrate: cannot write " + file + ": " + e.getMessage());
            return 1;
        }
        out.println("wrote " + file + " (nodes " + result.get("nodeIds") + ", invalid entries: "
            + ((java.util.List<?>) result.get("invalid")).size() + ")");
        return 0;
    }
}
