package com.numadisruptor.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-node resident-page summary of this process from {@code /proc/self/numa_maps}. Works without libnuma or privileges and is
 * the way to inspect where the (Java heap) pages that Java gives no placement API for actually ended up.
 */
public final class NumaMapsReader {
    private static final Pattern NODE_PAGES = Pattern.compile("\\bN(\\d+)=(\\d+)");
    private static final Pattern PAGE_SIZE_KB = Pattern.compile("kernelpagesize_kB=(\\d+)");

    private NumaMapsReader() { }

    /** Resident KiB per node over all mappings of this process, or empty if the file is unavailable. */
    public static Map<Integer, Long> residentKiBByNode() {
        return residentKiBByNode(Path.of("/proc/self/numa_maps"), 0);
    }

    /**
     * Resident KiB per node, counting only mappings of at least {@code minMappingKiB} (a rough way to isolate the Java heap
     * and other large arenas from the many small mappings).
     */
    public static Map<Integer, Long> residentKiBByNode(final Path numaMaps, final long minMappingKiB) {
        final Map<Integer, Long> out = new TreeMap<>();
        final List<String> lines;
        try {
            lines = Files.readAllLines(numaMaps, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return out;
        }
        for (final String line : lines) {
            final Matcher ps = PAGE_SIZE_KB.matcher(line);
            final long pageKiB = ps.find() ? Long.parseLong(ps.group(1)) : 4;
            final Matcher m = NODE_PAGES.matcher(line);
            final Map<Integer, Long> mapping = new TreeMap<>();
            long total = 0;
            while (m.find()) {
                final long kib = Long.parseLong(m.group(2)) * pageKiB;
                mapping.merge(Integer.parseInt(m.group(1)), kib, Long::sum);
                total += kib;
            }
            if (total >= minMappingKiB) {
                mapping.forEach((n, k) -> out.merge(n, k, Long::sum));
            }
        }
        return out;
    }
}
