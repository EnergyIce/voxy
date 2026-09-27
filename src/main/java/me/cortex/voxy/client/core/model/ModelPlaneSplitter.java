package me.cortex.voxy.client.core.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

//Voxy stores exactly one quad per face of a model (a rectangle at a single depth). Composite models -
// stairs, stepped/layered camouflage blocks (e.g. a glass step in front of a full brick wall) - have
// pixels at several different depths on the same face, which a single plane can only flatten.
//
//This splits such a model's face textures by depth into up to MAX_PLANES "plane models": plane 0 holds the
// nearest surface of every face, plane 1 the next one, and so on. Each plane model is an ordinary Voxy
// model (its own rectangle, depth and texture per face, pixels of other planes cleared), and the mesher
// emits one quad per plane (see RenderDataFactory's secondary passes), reconstructing the stepped shape.
//
//Sloped surfaces (Framed Blocks / Copycats+ slopes, prism corners, ...) seen along an axis have a continuous
// depth gradient instead of discrete steps. Such a depth range is cut into equal bands, one plane each, which
// turns the slope into a fine staircase - the closest a Voxy model (axis aligned quads only) can get - instead
// of one plane at the average depth (which flattened a full slope into a half block).
final class ModelPlaneSplitter {
    //Must not exceed 1 + RenderDataFactory.MAX_EXTRA_PLANES
    static final int MAX_PLANES = 5;
    //Depth (fraction of the block) that two surfaces must differ by to count as separate planes.
    // 0.09 is ~1.5 texels of the 16 texel face: smaller relief (brick texture depth etc.) stays one plane.
    private static final double MIN_GAP = 0.09;
    //A single continuous depth range wider than this is a slope and gets cut into bands
    private static final double SLOPE_MIN_RANGE = 0.15;
    //Bands of a slope are never made narrower than this (~2 texels); limits planes per face
    private static final double MIN_BAND_WIDTH = 0.12;
    //Planes with fewer pixels than this are noise, merged into the nearest plane instead.
    private static final int MIN_PIXELS = 4;
    //Small groups that fill less than 1/3 of their bounding box are lines - typically a surface seen exactly
    // edge-on (the rim of a slope). They must not become a plane of their own at their (usually far) depth.
    private static final int SLIVER_MAX_PIXELS = 24;

    private static final int DEPTH_MAX = (1 << 24) - 1;
    //What the rasteriser's cleared framebuffer looks like in the depth word: max depth, zero stencil
    private static final int CLEARED_DEPTH_WORD = 0xFFFFFF00;

    private ModelPlaneSplitter() {}

    private static final class Cluster {
        int minDepth = Integer.MAX_VALUE;
        int maxDepth = Integer.MIN_VALUE;
        int count;
        //Bounding box of its pixels (texel coordinates)
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        boolean noise;
        //Pixels of this cluster that lie on an inclined surface (see markSloped)
        int sloped;
        int bands = 1;
        int firstPlane;

        void add(int depth, int n) {
            this.minDepth = Math.min(this.minDepth, depth);
            this.maxDepth = Math.max(this.maxDepth, depth);
            this.count += n;
        }

        void merge(Cluster other) {
            this.minDepth = Math.min(this.minDepth, other.minDepth);
            this.maxDepth = Math.max(this.maxDepth, other.maxDepth);
            this.count += other.count;
            this.sloped += other.sloped;
            this.minX = Math.min(this.minX, other.minX);
            this.minY = Math.min(this.minY, other.minY);
            this.maxX = Math.max(this.maxX, other.maxX);
            this.maxY = Math.max(this.maxY, other.maxY);
        }

        int range() {
            return this.maxDepth - this.minDepth;
        }

        int bboxArea() {
            return (this.maxX - this.minX + 1) * (this.maxY - this.minY + 1);
        }

        //Band (0..bands-1) of a depth inside (or clamped to) this cluster's range
        int bandOf(int depth) {
            if (this.bands <= 1) return 0;
            long rel = (long) Math.min(Math.max(depth, this.minDepth), this.maxDepth) - this.minDepth;
            int b = (int) ((rel * this.bands) / ((long) this.range() + 1));
            return Math.min(Math.max(b, 0), this.bands - 1);
        }
    }

    //Returns null if the model does not need splitting (every face is a single plane)
    static ColourDepthTextureData[][] split(ColourDepthTextureData[] tex) {
        return split(tex, MAX_PLANES);
    }

    static ColourDepthTextureData[][] split(ColourDepthTextureData[] tex, int maxPlanes) {
        return split(tex, maxPlanes, true);
    }

    //extendBands: every band of a slope is also extended over the area of all nearer bands of the same slope, at its
    // own depth ("shingles"). Seen head-on the nearer band hides that extension, so nothing changes; seen at an
    // angle it fills the gap where the step riser would be - without it a sloped face turned into see-through
    // slats. Must be false for translucent layers (overlapping glass would be blended twice).
    static ColourDepthTextureData[][] split(ColourDepthTextureData[] tex, int maxPlanes, boolean extendBands) {
        return split(tex, maxPlanes, extendBands, 0);
    }

    //lip: every plane is also drawn over the pixels of deeper planes within this many texels of its own pixels, at its
    // own depth and with their own colour ("lip"). Seen head-on the nearer plane shows exactly the colour that was
    // there, so nothing changes; seen at an angle it covers the riser between two steps. The staircases of two
    // perpendicular faces are cut independently, so their steps don't always meet - without the lip a grazing view
    // slips through between a step of one face and the step edge of the other.
    static ColourDepthTextureData[][] split(ColourDepthTextureData[] tex, int maxPlanes, boolean extendBands, int lip) {
        maxPlanes = Math.max(1, Math.min(maxPlanes, MAX_PLANES));
        final int gap = (int) Math.round(MIN_GAP * DEPTH_MAX);
        final int slopeRange = (int) Math.round(SLOPE_MIN_RANGE * DEPTH_MAX);
        final int minBand = (int) Math.round(MIN_BAND_WIDTH * DEPTH_MAX);
        final int[][] planeOf = new int[6][];
        //Depth word per pixel for the output (differs from the source only for reassigned noise pixels)
        final int[][] depthOut = new int[6][];
        //Last plane each pixel is drawn into (== its own plane unless extended as a shingle)
        final int[][] lastPlaneOf = new int[6][];
        //First (nearest) plane each pixel is drawn into (== its own plane unless covered by the lip of a nearer plane)
        final int[][] firstPlaneOf = new int[6][];
        int planes = 1;

        for (int face = 0; face < 6; face++) {
            var src = tex[face];
            var depth = src.depth();
            final int width = src.width();
            int n = depth.length;
            int[] p = new int[n];
            Arrays.fill(p, -1);
            planeOf[face] = p;
            firstPlaneOf[face] = p;
            int[] dOut = depth.clone();
            depthOut[face] = dOut;
            int[] lastP = new int[n];
            Arrays.fill(lastP, -1);
            lastPlaneOf[face] = lastP;

            //Collect the depths of every written pixel (stencil != 0)
            int written = 0;
            int[] values = new int[n];
            for (int i = 0; i < n; i++) {
                if ((depth[i] & 0xFF) != 0) {
                    values[written++] = depth[i] >>> 8;
                }
            }
            if (written == 0) {
                continue;
            }
            Arrays.sort(values, 0, written);

            //1D clustering: a new cluster starts wherever consecutive sorted depths jump by more than gap.
            // Clusters are ordered by depth and their ranges are disjoint.
            List<Cluster> clusters = new ArrayList<>();
            Cluster cur = null;
            int last = 0;
            for (int i = 0; i < written; i++) {
                int d = values[i];
                if (cur == null || d - last > gap) {
                    cur = new Cluster();
                    clusters.add(cur);
                }
                cur.add(d, 1);
                last = d;
            }

            //Pixel -> gap cluster, and each cluster's bounding box
            int[] rawIdx = new int[n];
            Arrays.fill(rawIdx, -1);
            for (int i = 0; i < n; i++) {
                if ((depth[i] & 0xFF) == 0) continue;
                int c = indexOfRange(clusters, depth[i] >>> 8);
                rawIdx[i] = c;
                var cl = clusters.get(c);
                int x = i % width, y = i / width;
                cl.minX = Math.min(cl.minX, x);
                cl.maxX = Math.max(cl.maxX, x);
                cl.minY = Math.min(cl.minY, y);
                cl.maxY = Math.max(cl.maxY, y);
            }

            //A steep slope advances by more than the gap threshold per texel, so gap clustering cuts it into
            // 1-texel strips at different depths. Rejoin neighbouring (in depth) clusters that consist mostly of
            // inclined pixels into one continuous range, so it is banded and shingled like any other slope.
            if (clusters.size() > 1) {
                boolean[] sloped = markSloped(depth, width, src.height());
                for (int i = 0; i < n; i++) {
                    if (rawIdx[i] >= 0 && sloped[i]) clusters.get(rawIdx[i]).sloped++;
                }
                int[] remap = new int[clusters.size()];
                List<Cluster> joined = new ArrayList<>();
                for (int c = 0; c < clusters.size(); c++) {
                    var cl = clusters.get(c);
                    boolean mostlySloped = cl.sloped * 2 >= cl.count;
                    if (!joined.isEmpty() && mostlySloped && lastWasSloped(joined)) {
                        joined.get(joined.size() - 1).merge(cl);
                    } else {
                        joined.add(cl);
                    }
                    remap[c] = joined.size() - 1;
                }
                if (joined.size() != clusters.size()) {
                    for (int i = 0; i < n; i++) {
                        if (rawIdx[i] >= 0) rawIdx[i] = remap[rawIdx[i]];
                    }
                    clusters = joined;
                }
            }

            //Noise: tiny groups and thin lines. Never all of them - the largest cluster always stays.
            int largest = 0;
            for (int c = 1; c < clusters.size(); c++) {
                if (clusters.get(c).count > clusters.get(largest).count) largest = c;
            }
            List<Cluster> kept = new ArrayList<>();
            for (int c = 0; c < clusters.size(); c++) {
                var cl = clusters.get(c);
                cl.noise = c != largest && (cl.count < MIN_PIXELS || (cl.count <= SLIVER_MAX_PIXELS && cl.count * 3 < cl.bboxArea()));
                if (!cl.noise) kept.add(cl);
            }

            //Enforce the plane limit by merging the smallest kept cluster into its depth-closest kept neighbour
            // (kept stays ordered by depth; merging adjacent ranges keeps them disjoint)
            while (kept.size() > maxPlanes) {
                int smallest = 0;
                for (int i = 1; i < kept.size(); i++) {
                    if (kept.get(i).count < kept.get(smallest).count) smallest = i;
                }
                int into;
                if (smallest == 0) {
                    into = 1;
                } else if (smallest == kept.size() - 1) {
                    into = smallest - 1;
                } else {
                    long dPrev = (long) kept.get(smallest).minDepth - kept.get(smallest - 1).maxDepth;
                    long dNext = (long) kept.get(smallest + 1).minDepth - kept.get(smallest).maxDepth;
                    into = dPrev <= dNext ? smallest - 1 : smallest + 1;
                }
                kept.get(into).merge(kept.get(smallest));
                kept.remove(smallest);
            }

            //Slopes: hand the remaining plane budget to the widest continuous ranges, one band at a time,
            // as long as the resulting bands stay at least minBand wide
            int used = kept.size();
            while (used < maxPlanes) {
                Cluster best = null;
                long bestWidth = 0;
                for (var cl : kept) {
                    if (cl.range() <= slopeRange) continue;
                    long bandWidth = (long) cl.range() / cl.bands;
                    long nextWidth = (long) cl.range() / (cl.bands + 1);
                    if (nextWidth >= minBand && bandWidth > bestWidth) {
                        best = cl;
                        bestWidth = bandWidth;
                    }
                }
                if (best == null) break;
                best.bands++;
                used++;
            }

            int next = 0;
            for (var cl : kept) {
                cl.firstPlane = next;
                next += cl.bands;
            }

            //Final plane of every written pixel. Noise pixels (and pixels of clusters merged away) go to the kept
            // cluster whose depth range is closest to their own depth.
            for (int i = 0; i < n; i++) {
                if (rawIdx[i] < 0) continue;
                int d = depth[i] >>> 8;
                var own = clusters.get(rawIdx[i]);
                Cluster target = null;
                if (!own.noise) {
                    for (var cl : kept) {
                        if (d >= cl.minDepth && d <= cl.maxDepth) {
                            target = cl;
                            break;
                        }
                    }
                }
                if (target == null) {
                    long bestDist = Long.MAX_VALUE;
                    for (var cl : kept) {
                        long dist = d < cl.minDepth ? (long) cl.minDepth - d : (d > cl.maxDepth ? (long) d - cl.maxDepth : 0);
                        if (dist < bestDist) {
                            bestDist = dist;
                            target = cl;
                        }
                    }
                }
                if (d < target.minDepth || d > target.maxDepth) {
                    //Moved to another plane: clamp its depth into that plane's range, otherwise these few pixels
                    // would drag the plane's (averaged) depth and shift the whole face in or out of the block
                    int clamped = Math.min(Math.max(d, target.minDepth), target.maxDepth);
                    dOut[i] = (clamped << 8) | (depth[i] & 0xFF);
                    d = clamped;
                }
                p[i] = target.firstPlane + target.bandOf(d);
                lastP[i] = extendBands ? target.firstPlane + target.bands - 1 : p[i];
            }
            planes = Math.max(planes, next);

            int[] firstP = p.clone();
            if (lip > 0) {
                final int height = src.height();
                for (int i = 0; i < n; i++) {
                    if (p[i] < 0) continue;
                    int x = i % width, y = i / width;
                    for (int dy = -lip; dy <= lip; dy++) {
                        for (int dx = -lip; dx <= lip; dx++) {
                            if (Math.abs(dx) + Math.abs(dy) > lip) continue;
                            int nx = x + dx, ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                            int j = ny * width + nx;
                            if (p[j] >= 0 && p[j] < firstP[i]) firstP[i] = p[j];
                        }
                    }
                }
            }
            firstPlaneOf[face] = firstP;
        }

        if (planes <= 1) {
            return null;
        }

        var out = new ColourDepthTextureData[planes][6];
        for (int plane = 0; plane < planes; plane++) {
            for (int face = 0; face < 6; face++) {
                var src = tex[face];
                int[] colour = new int[src.colour().length];
                int[] depth = new int[src.depth().length];
                Arrays.fill(depth, CLEARED_DEPTH_WORD);
                int[] p = planeOf[face];
                int[] lastP = lastPlaneOf[face];
                int[] firstP = firstPlaneOf[face];
                //Mean depth of this plane's own pixels: extended (shingle) pixels are placed at it, so they don't
                // change the plane's computed depth
                long sum = 0;
                int cnt = 0;
                for (int i = 0; i < p.length; i++) {
                    if (p[i] == plane) {
                        sum += depthOut[face][i] >>> 8;
                        cnt++;
                    }
                }
                for (int i = 0; i < p.length; i++) {
                    if (p[i] == plane) {
                        colour[i] = src.colour()[i];
                        depth[i] = depthOut[face][i];
                    } else if (cnt != 0 && p[i] >= 0 && ((p[i] < plane && plane <= lastP[i]) || (firstP[i] <= plane && plane < p[i]))) {
                        colour[i] = src.colour()[i];
                        depth[i] = ((int) (sum / cnt) << 8) | (depthOut[face][i] & 0xFF);
                    }
                }
                out[plane][face] = new ColourDepthTextureData(colour, depth, src.width(), src.height());
            }
        }
        return out;
    }

    private static boolean lastWasSloped(List<Cluster> joined) {
        var last = joined.get(joined.size() - 1);
        return last.sloped * 2 >= last.count;
    }

    //Marks pixels that lie on an inclined surface: along a row or a column, three consecutive written texels whose
    // depth changes twice in the same direction by a similar, moderate step. Flat surfaces (step 0) and the single
    // jumps of stairs, bytes or layers never qualify; only surfaces seen at an angle do.
    private static boolean[] markSloped(int[] depth, int width, int height) {
        final int minStep = (int) Math.round(0.02 * DEPTH_MAX);
        final int maxStep = (int) Math.round(0.45 * DEPTH_MAX);
        final int tolerance = (int) Math.round(0.08 * DEPTH_MAX);
        boolean[] out = new boolean[depth.length];
        for (int axis = 0; axis < 2; axis++) {
            int stride = axis == 0 ? 1 : width;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (axis == 0 ? (x < 1 || x >= width - 1) : (y < 1 || y >= height - 1)) continue;
                    int i = y * width + x;
                    int a = i - stride, c = i + stride;
                    if ((depth[a] & 0xFF) == 0 || (depth[i] & 0xFF) == 0 || (depth[c] & 0xFF) == 0) continue;
                    int d1 = (depth[i] >>> 8) - (depth[a] >>> 8);
                    int d2 = (depth[c] >>> 8) - (depth[i] >>> 8);
                    if (Integer.signum(d1) == 0 || Integer.signum(d1) != Integer.signum(d2)) continue;
                    int m1 = Math.abs(d1), m2 = Math.abs(d2);
                    if (m1 < minStep || m2 < minStep || m1 > maxStep || m2 > maxStep) continue;
                    if (Math.abs(m1 - m2) > tolerance) continue;
                    out[a] = true;
                    out[i] = true;
                    out[c] = true;
                }
            }
        }
        return out;
    }

    //Index of the (ordered, disjoint) cluster range containing depth d
    private static int indexOfRange(List<Cluster> clusters, int d) {
        for (int c = 0; c < clusters.size(); c++) {
            if (d <= clusters.get(c).maxDepth) return c;
        }
        return clusters.size() - 1;
    }
}
