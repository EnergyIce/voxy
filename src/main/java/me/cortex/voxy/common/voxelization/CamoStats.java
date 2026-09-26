package me.cortex.voxy.common.voxelization;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

//Lightweight per-block-type event counters for the camouflage/mimicry pipeline (ingest -> bake), so
// gaps in the LOD can be attributed to a concrete stage from a log instead of guessing. Events are
// things like "ingest.withBE", "ingest.noBE", "bake.ok", "bake.noQuads", "bake.exception".
public final class CamoStats {
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, LongAdder>> STATS = new ConcurrentHashMap<>();
    private static volatile String lastReported = "";

    private CamoStats() {}

    public static void count(String block, String event) {
        STATS.computeIfAbsent(block, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(event, k -> new LongAdder()).increment();
    }

    //True for the first few occurrences of (block, kind), so detailed per-instance logs stay bounded.
    public static boolean shouldDetail(String block, String kind) {
        if (!CamoDebug.DIAGNOSTICS) return false;
        int limit = 3;
        if ("dump".equals(kind)) limit = 2;
        //Half-layer style multi-state blocks have many variants that look very different per state; log more of them
        if ("dumpPlane".equals(kind)) limit = block.contains("half_layer") ? 40 : 3;
        return bump(block + "|" + kind) <= limit;
    }

    private static final ConcurrentHashMap<String, Integer> DETAIL_COUNT = new ConcurrentHashMap<>();
    private static int bump(String k) {
        return DETAIL_COUNT.merge(k, 1, Integer::sum);
    }

    //Returns a printable summary if anything changed since the last call, otherwise null.
    public static String reportIfChanged() {
        if (!CamoDebug.DIAGNOSTICS) return null;
        var sorted = new TreeMap<String, Map<String, Long>>();
        for (var e : STATS.entrySet()) {
            var m = new TreeMap<String, Long>();
            for (var ev : e.getValue().entrySet()) m.put(ev.getKey(), ev.getValue().sum());
            sorted.put(e.getKey(), m);
        }
        if (sorted.isEmpty()) return null;
        var sb = new StringBuilder();
        for (var e : sorted.entrySet()) {
            sb.append("\n  ").append(e.getKey()).append(": ").append(e.getValue());
        }
        String s = sb.toString();
        if (s.equals(lastReported)) return null;
        lastReported = s;
        return s;
    }
}
