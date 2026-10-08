package com.numadisruptor.topology;

import java.util.BitSet;

/** Parser/formatter for the Linux "cpulist" syntax, e.g. {@code 0-7,16-23}. */
public final class CpuList {
    private CpuList() { }

    public static BitSet parse(final String text) {
        final BitSet out = new BitSet();
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return out;
        }
        for (final String part : trimmed.split(",")) {
            final String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            final int dash = p.indexOf('-');
            if (dash < 0) {
                out.set(Integer.parseInt(p));
            } else {
                final int lo = Integer.parseInt(p.substring(0, dash));
                final int hi = Integer.parseInt(p.substring(dash + 1));
                if (hi < lo) {
                    throw new IllegalArgumentException("Invalid range '" + p + "' in cpulist '" + text + "'");
                }
                out.set(lo, hi + 1);
            }
        }
        return out;
    }

    public static String format(final BitSet set) {
        final StringBuilder sb = new StringBuilder();
        int i = set.nextSetBit(0);
        while (i >= 0) {
            int end = i;
            while (set.get(end + 1)) {
                end++;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(i);
            if (end > i) {
                sb.append('-').append(end);
            }
            i = set.nextSetBit(end + 1);
        }
        return sb.toString();
    }
}
