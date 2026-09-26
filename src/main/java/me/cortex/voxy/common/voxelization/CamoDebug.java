package me.cortex.voxy.common.voxelization;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

//Runtime switches for the camouflage/mimicry LOD pipeline, read once at startup from
// <game dir>/config/voxy_camo_debug.properties (created with all switches on if missing). They exist to
// bisect rendering problems without rebuilding: every switch defaults to the shipped behaviour.
public final class CamoDebug {
    //Master switch. false = the whole camo pipeline is off (no ingest tagging, no baking, no instance
    // override on stored voxels) - Voxy behaves like it did without camouflage support.
    public static final boolean ENABLED;
    //Instance models are flagged single sided; their quads are discarded when seen from behind (shader)
    public static final boolean SINGLE_SIDED;
    //Split stepped/layered instance models into several depth-plane models (extra mesher passes)
    public static final boolean MULTI_PLANE;
    //Keep glass and opaque parts of one block as separate layers instead of one blended composite
    public static final boolean MIXED_LAYERS;
    //Rasterise instance bakes two sided (both windings)
    public static final boolean TWO_SIDED;
    //Decide face occlusion of translucent instance models per face instead of per model layer
    public static final boolean FACE_OCCLUSION;
    //Diagnostics for bug hunting: per-block stats in the log every 20s, detailed per-bake log lines and PNG dumps
    // of baked face textures into <game dir>/voxy_camo_dump. OFF by default (unlike the switches above).
    public static final boolean DIAGNOSTICS;

    static {
        boolean[] v = {true, true, true, true, true, true};
        boolean diagnostics = false;
        String[] keys = {"enabled", "singleSided", "multiPlane", "mixedLayers", "twoSided", "faceOcclusion"};
        Path path = Path.of("config", "voxy_camo_debug.properties");
        Properties props = new Properties();
        try {
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                }
                for (int i = 0; i < keys.length; i++) {
                    v[i] = Boolean.parseBoolean(props.getProperty(keys[i], "true").trim());
                }
                diagnostics = Boolean.parseBoolean(props.getProperty("diagnostics", "false").trim());
            } else {
                Files.createDirectories(path.getParent());
                for (String k : keys) props.setProperty(k, "true");
                props.setProperty("diagnostics", "false");
                try (OutputStream out = Files.newOutputStream(path)) {
                    props.store(out, "Voxy camo debug switches (true = on). enabled=false turns the whole camouflage pipeline off. Restart the game after editing.");
                }
            }
        } catch (IOException | RuntimeException e) {
            //Fall back to the defaults
        }
        ENABLED = v[0];
        SINGLE_SIDED = v[1];
        MULTI_PLANE = v[2];
        MIXED_LAYERS = v[3];
        TWO_SIDED = v[4];
        FACE_OCCLUSION = v[5];
        DIAGNOSTICS = diagnostics;
    }

    private CamoDebug() {}
}
