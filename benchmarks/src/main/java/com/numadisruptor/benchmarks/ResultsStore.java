package com.numadisruptor.benchmarks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Append-only results: one JSON object per line ({@code results.jsonl}, the full record) plus a flat {@code results.csv}.
 * Every append is written and forced to disk before returning, so if the allocation is killed, all completed trials survive.
 */
final class ResultsStore {
    static final List<String> CSV_COLUMNS = List.of(
        "id", "trial", "status", "reason", "variant", "locality", "ringHome", "offheap", "partitions", "producers", "consumers",
        "stages", "mode", "ring", "bytes", "wait", "work", "metric", "rate", "measureSeconds", "workloadKey",
        "eventsPerSec", "eventsMeasured", "p50Ns", "p95Ns", "p99Ns", "p999Ns", "maxNs", "nanoTimeOverheadNs",
        "producerStallsInWindow", "stallsPerMillionEvents", "cpuUtilPercent", "migrationsObserved", "workersOnMultipleCpus",
        "gcCollections", "gcPauseMillis", "allocatedBytesPerEvent", "optimizerNanos", "modelCost", "pinned",
        "published", "delivered", "lost", "outOfOrder", "payloadErrors", "orderChecksum",
        "gitCommit", "host", "timestamp");

    private static final Pattern ID = Pattern.compile("\"id\":\"([^\"]*)\",\"trial\":(\\d+)");

    private final Path dir;
    private final Path jsonl;
    private final Path csv;

    ResultsStore(final Path dir) throws IOException {
        Files.createDirectories(dir);
        this.dir = dir;
        jsonl = dir.resolve("results.jsonl");
        csv = dir.resolve("results.csv");
        if (!Files.exists(csv)) {
            Files.writeString(csv, String.join(",", CSV_COLUMNS) + "\n", StandardCharsets.UTF_8);
        }
    }

    /** "id#trial" keys of every record already stored (any status), for resuming a partially finished matrix. */
    Set<String> completedKeys() throws IOException {
        final Set<String> keys = new HashSet<>();
        if (Files.exists(jsonl)) {
            for (final String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
                if (line.contains("\"status\":\"skipped\"")) {
                    continue; // a different allocation may have the topology this one lacked: retry
                }
                final Matcher m = ID.matcher(line);
                if (m.find()) {
                    keys.add(m.group(1) + "#" + m.group(2));
                }
            }
        }
        return keys;
    }

    Path dir() {
        return dir;
    }

    static String key(final String id, final int trial) {
        return id + "#" + trial;
    }

    synchronized void append(final Map<String, Object> result) throws IOException {
        write(jsonl, MiniJson.write(result) + "\n");
        write(csv, csvRow(result) + "\n");
    }

    private static void write(final Path p, final String text) throws IOException {
        try (var ch = java.nio.channels.FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
    }

    static String csvRow(final Map<String, Object> r) {
        final StringBuilder sb = new StringBuilder();
        for (final String col : CSV_COLUMNS) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(cell(value(r, col)));
        }
        return sb.toString();
    }

    private static String cell(final Object v) {
        if (v == null) {
            return "";
        }
        final String s = v instanceof Double d && (d.isNaN() || d.isInfinite()) ? "" : String.valueOf(v);
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    @SuppressWarnings("unchecked")
    private static Object path(final Map<String, Object> r, final String... keys) {
        Object cur = r;
        for (final String k : keys) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = ((Map<String, Object>) m).get(k);
        }
        return cur;
    }

    private static Object value(final Map<String, Object> r, final String col) {
        return switch (col) {
            case "id", "trial", "status", "reason", "workloadKey" -> r.get(col);
            case "variant", "locality", "ringHome", "offheap", "partitions", "producers", "consumers", "stages", "mode", "ring",
                 "bytes", "wait", "work", "metric", "rate" -> path(r, "config", col);
            case "measureSeconds" -> path(r, "config", "measure");
            case "eventsPerSec" -> path(r, "throughput", "eventsPerSec");
            case "eventsMeasured" -> path(r, "throughput", "eventsMeasured");
            case "p50Ns", "p95Ns", "p99Ns", "p999Ns", "maxNs", "nanoTimeOverheadNs" -> path(r, "latency", col);
            case "producerStallsInWindow", "stallsPerMillionEvents" -> path(r, "backpressure", col);
            case "cpuUtilPercent" -> path(r, "cpu", "processUtilizationPercent");
            case "migrationsObserved" -> path(r, "cpu", col);
            case "workersOnMultipleCpus" -> path(r, "cpu", "workersSeenOnMoreThanOneCpu");
            case "gcCollections" -> path(r, "gc", "collectionsInWindow");
            case "gcPauseMillis" -> path(r, "gc", "pauseMillisInWindow");
            case "allocatedBytesPerEvent" -> path(r, "gc", col);
            case "optimizerNanos", "modelCost", "pinned" -> path(r, "placement", col);
            case "published", "delivered", "lost", "outOfOrder", "payloadErrors", "orderChecksum" -> path(r, "correctness", col);
            case "gitCommit", "host", "timestamp" -> path(r, "metadata", col);
            default -> null;
        };
    }
}
