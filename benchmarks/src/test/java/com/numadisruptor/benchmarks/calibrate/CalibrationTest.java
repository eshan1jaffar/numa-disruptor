package com.numadisruptor.benchmarks.calibrate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.numadisruptor.affinity.CpuAffinity;
import com.numadisruptor.benchmarks.MiniJson;
import com.numadisruptor.memory.NumaAllocator;
import com.numadisruptor.optimizer.PlacementCostModel;
import com.numadisruptor.topology.TopologyDiscovery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CalibrationTest {
    @TempDir Path dir;

    private static final PlacementCostModel.Coefficients C = PlacementCostModel.Coefficients.defaults();

    private Path fixture(final String json) throws IOException {
        return Files.writeString(dir.resolve("c.json"), json);
    }

    @Test
    void cycleIsSingleCycleCoveringAllLinesAndDeterministic() {
        final int n = 1000;
        final int[] next = PointerChase.buildCycle(n, 7);
        final boolean[] seen = new boolean[n];
        int at = 0;
        for (int i = 0; i < n; i++) {
            assertFalse(seen[at], "revisited line " + at + " after " + i + " steps");
            seen[at] = true;
            at = next[at];
        }
        assertEquals(0, at, "must return to the start after exactly n steps");
        assertArrayEquals(next, PointerChase.buildCycle(n, 7));
        assertFalse(Arrays.equals(next, PointerChase.buildCycle(n, 8)));
        assertThrows(IllegalArgumentException.class, () -> PointerChase.buildCycle(1, 0));
    }

    @Test
    void loadsSyntheticMatrix() throws IOException {
        // SYNTHETIC test data, not a measurement.
        final PlacementCostModel m = CalibratedCostModel.load(fixture(
            "{\"nodeIds\":[0,1],\"latencyNs\":[[100.0,250.0],[200.0,100.0]]}"), C, List.of(0, 1));
        assertEquals(1.5, m.excessDistance(0, 1), 1e-9);
        assertEquals(1.0, m.excessDistance(1, 0), 1e-9);
        assertEquals(0.0, m.excessDistance(1, 1), 1e-9);
    }

    @Test
    void rejectsNullNonPositiveShapeAndNodeMismatch() throws IOException {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> CalibratedCostModel.load(fixture(
            "{\"nodeIds\":[0,1],\"latencyNs\":[[100.0,null],[200.0,100.0]]}"), C)).getMessage().contains("invalid"));
        assertThrows(IllegalArgumentException.class, () -> CalibratedCostModel.load(fixture(
            "{\"nodeIds\":[0],\"latencyNs\":[[0.0]]}"), C));
        assertThrows(IllegalArgumentException.class, () -> CalibratedCostModel.load(fixture(
            "{\"nodeIds\":[0,1],\"latencyNs\":[[100.0,1.0]]}"), C));
        assertThrows(IllegalArgumentException.class, () -> CalibratedCostModel.load(fixture(
            "{\"nodeIds\":[0,0],\"latencyNs\":[[1.0,1.0],[1.0,1.0]]}"), C));
        assertThrows(IllegalArgumentException.class, () -> CalibratedCostModel.load(fixture(
            "{\"nodeIds\":[0],\"latencyNs\":[[100.0]]}"), C, List.of(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> CalibratedCostModel.load(fixture("not json"), C));
        assertThrows(IllegalArgumentException.class, () -> CalibratedCostModel.load(fixture("{}"), C));
    }

    @Test
    @Tag("hardware")
    void tinyCalibrationOnThisMachine() throws IOException {
        Assumptions.assumeTrue(NumaAllocator.isAvailable(), "libnuma not available");
        final CpuAffinity aff = CpuAffinity.system();
        Assumptions.assumeTrue(aff.isSupported(), "affinity unsupported");
        final var topo = TopologyDiscovery.discover();
        final var r = Calibrator.run(topo, aff, NumaAllocator.open(), new Calibrator.Params(8L << 20, 3, 200_000, 20_000, 1));
        final int n = topo.usableNodes().size();
        assertEquals(n, ((List<?>) r.get("nodeIds")).size());
        final Path out = dir.resolve("out.json");
        Files.writeString(out, MiniJson.write(r));
        final Map<String, Object> back = MiniJson.parseObject(Files.readString(out));
        final List<?> invalid = (List<?>) back.get("invalid");
        for (final String key : List.of("latencyNs", "latencyMinNs", "latencyMaxNs", "pingPongNs")) {
            final List<?> rows = (List<?>) back.get(key);
            assertEquals(n, rows.size());
            for (final Object row : rows) {
                assertEquals(n, ((List<?>) row).size());
                for (final Object v : (List<?>) row) {
                    if (v != null) {
                        assertTrue(((Number) v).doubleValue() > 0, key + " must be positive");
                    } else {
                        assertFalse(invalid.isEmpty(), key + " has null without an invalid entry");
                    }
                }
            }
        }
        System.out.println("CALIBRATION " + MiniJson.write(Map.of("latencyNs", r.get("latencyNs"), "min", r.get("latencyMinNs"),
            "max", r.get("latencyMaxNs"), "pingPongNs", r.get("pingPongNs"), "invalid", invalid)));
        assertNotEquals(0, ((Map<?, ?>) back.get("metadata")).size());
    }
}
