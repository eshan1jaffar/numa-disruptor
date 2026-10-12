package com.numadisruptor.benchmarks.calibrate;

import com.numadisruptor.benchmarks.MiniJson;
import com.numadisruptor.optimizer.PlacementCostModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a {@link PlacementCostModel} from the JSON written by {@link CalibrationMain}, refusing anything incomplete. */
public final class CalibratedCostModel {
    private CalibratedCostModel() { }

    public static PlacementCostModel load(final Path json, final PlacementCostModel.Coefficients coefficients) throws IOException {
        return load(json, coefficients, null);
    }

    /** @param expectedNodes if non-null, the file's node set must equal it (for example the topology's usable nodes) */
    public static PlacementCostModel load(final Path json, final PlacementCostModel.Coefficients coefficients,
                                          final Collection<Integer> expectedNodes) throws IOException {
        final Map<String, Object> doc;
        try {
            doc = MiniJson.parseObject(Files.readString(json, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("calibration file " + json + " is not valid JSON: " + e, e);
        }
        if (!(doc.get("nodeIds") instanceof List<?> ids) || ids.isEmpty() || !(doc.get("latencyNs") instanceof List<?> rows)) {
            throw new IllegalArgumentException("calibration file " + json + " lacks nodeIds / latencyNs");
        }
        final int n = ids.size();
        final int[] nodeIds = new int[n];
        final Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (!(ids.get(i) instanceof Number id) || !seen.add(id.intValue())) {
                throw new IllegalArgumentException("nodeIds must be distinct integers: " + ids);
            }
            nodeIds[i] = id.intValue();
        }
        if (expectedNodes != null && !seen.equals(Set.copyOf(expectedNodes))) {
            throw new IllegalArgumentException("calibration was measured for nodes " + seen + " but the topology has " + expectedNodes);
        }
        if (rows.size() != n) {
            throw new IllegalArgumentException("latencyNs has " + rows.size() + " rows for " + n + " nodes");
        }
        final double[][] lat = new double[n][n];
        for (int i = 0; i < n; i++) {
            if (!(rows.get(i) instanceof List<?> row) || row.size() != n) {
                throw new IllegalArgumentException("latencyNs row " + i + " must have " + n + " entries");
            }
            for (int j = 0; j < n; j++) {
                if (!(row.get(j) instanceof Number v) || Double.isNaN(v.doubleValue()) || Double.isInfinite(v.doubleValue())
                    || v.doubleValue() <= 0) {
                    throw new IllegalArgumentException("latencyNs[" + nodeIds[i] + "][" + nodeIds[j] + "] is invalid (" + row.get(j)
                        + "); the pair was not measured, see the file's \"invalid\" list");
                }
                lat[i][j] = v.doubleValue();
            }
        }
        return PlacementCostModel.fromLatencyMatrix(nodeIds, lat, coefficients);
    }
}
