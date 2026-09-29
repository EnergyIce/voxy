package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;

//Tells the LoD shaders which chunk sections vanilla (sodium/embeddium) is drawing, so LoD geometry is dropped exactly
// there and nowhere else.
//
// This used to rasterize the bounding boxes of all built sections into a depth buffer and drop every LoD fragment
// closer than the farthest box along that pixel. That also dropped LoD geometry in sections vanilla does NOT draw
// whenever a drawn section was somewhere behind it on the same pixel (unbuilt chunks along the edge of the vanilla
// area, chunks still waiting for their neighbours), and its distance test was one block more generous than
// embeddium's - both showed up as invisible chunks along the render distance border.
//
// Now every LoD fragment looks up the section it lies in, in a bitmask of built sections, and repeats sodium's own
// render distance test for that section (same camera rounding, same search distance, same section padding).
// Only if both say "vanilla draws this" is the fragment discarded.
public class ChunkBoundRenderer {
    //Header layout (per viewport, Viewport.vanillaMaskHeader), must match VanillaMaskHeader in quads.frag
    //  ivec4 camInt  : xyz = camera block pos truncated like sodium's CameraTransform, w = masking enabled
    //  vec4  camFrac : xyz = camera fraction like sodium's CameraTransform, w = sodium search distance in blocks
    //  ivec4 origin  : xyz = section coords of grid cell 0, w = sodium's section padding
    //  ivec4 size    : x = grid size on x/z, y = grid size on y
    private static final int HEADER_SIZE = 64;

    //Section padding sodium applies to a section's AABB in OcclusionCuller.isWithinRenderDistance
    //? if 1.21.1 {
    private static final int SODIUM_SECTION_PADDING = 1;
    //? } else {
    private static final int SODIUM_SECTION_PADDING = 0;
    //? }

    //How far (in chunks) the camera may move from the grid centre before the grid is re-centred
    private static final int RECENTER_SLACK = 4;

    //Search distance (blocks) of sodium's last visibility update, captured from RenderSectionManager#getSearchDistance
    private static volatile float sodiumSearchDistance = Float.NaN;

    public static void setSodiumSearchDistance(float distance) {
        sodiumSearchDistance = distance;
    }

    private final LongOpenHashSet sections = new LongOpenHashSet();

    private GlBuffer maskBuffer = new GlBuffer(4);
    private int[] bits = new int[0];
    private boolean needsRebuild = true;
    private int dirtyMin = Integer.MAX_VALUE;
    private int dirtyMax = -1;

    private int gridSize;//x/z size in sections, odd, centred on (centreX, centreZ)
    private int centreX, centreZ;
    private int originX, originY, originZ;
    private int sizeY;

    public ChunkBoundRenderer(AbstractRenderPipeline pipeline) {
    }

    public void addSection(long pos) {
        if (this.sections.add(pos)) {
            this.setBit(pos, true);
        }
    }

    public void removeSection(long pos) {
        if (this.sections.remove(pos)) {
            this.setBit(pos, false);
        }
    }

    private void setBit(long pos, boolean value) {
        if (this.needsRebuild) return;
        int y = SectionPos.y(pos) - this.originY;
        if (y < 0 || y >= this.sizeY) {
            if (value) this.needsRebuild = true;//Grow the y range
            return;
        }
        int idx = this.index(SectionPos.x(pos) - this.originX, y, SectionPos.z(pos) - this.originZ);
        if (idx < 0) return;//Outside the grid, picked up when the grid is re-centred
        int word = idx >>> 5;
        if (value) {
            this.bits[word] |= 1 << (idx & 31);
        } else {
            this.bits[word] &= ~(1 << (idx & 31));
        }
        this.dirtyMin = Math.min(this.dirtyMin, word);
        this.dirtyMax = Math.max(this.dirtyMax, word);
    }

    private int index(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= this.gridSize || z >= this.gridSize) return -1;
        return (x * this.gridSize + z) * this.sizeY + y;
    }

    private void rebuild(int camSecX, int camSecZ, int renderDistance) {
        var level = Minecraft.getInstance().level;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        if (level != null) {
            minY = level.getMinSection();
            maxY = level.getMaxSection() - 1;
        }
        var it = this.sections.iterator();
        while (it.hasNext()) {
            int y = SectionPos.y(it.nextLong());
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        if (minY > maxY) {
            minY = 0;
            maxY = 0;
        }

        int half = renderDistance + 2 + RECENTER_SLACK;
        this.gridSize = half * 2 + 1;
        this.centreX = camSecX;
        this.centreZ = camSecZ;
        this.originX = camSecX - half;
        this.originZ = camSecZ - half;
        this.originY = minY;
        this.sizeY = maxY - minY + 1;

        int words = (int) (((long) this.gridSize * this.gridSize * this.sizeY + 31) >>> 5);
        if (this.bits.length != words) {
            this.bits = new int[words];
        } else {
            java.util.Arrays.fill(this.bits, 0);
        }

        it = this.sections.iterator();
        while (it.hasNext()) {
            long pos = it.nextLong();
            int idx = this.index(SectionPos.x(pos) - this.originX, SectionPos.y(pos) - this.originY, SectionPos.z(pos) - this.originZ);
            if (idx >= 0) {
                this.bits[idx >>> 5] |= 1 << (idx & 31);
            }
        }

        long needed = Math.max(words, 1) * 4L;
        if (this.maskBuffer.size() < needed) {
            this.maskBuffer.free();
            this.maskBuffer = new GlBuffer(needed);
        }

        this.needsRebuild = false;
        this.dirtyMin = 0;
        this.dirtyMax = words - 1;
    }

    //Updates the mask for this viewport; enabled=false makes the LoD shaders ignore it (nothing gets discarded)
    public void render(Viewport<?> viewport, boolean enabled) {
        viewport.vanillaSectionMask = this.maskBuffer;

        float searchDistance = sodiumSearchDistance;
        int optionsDistance = Minecraft.getInstance().options.getEffectiveRenderDistance();
        if (!(searchDistance > 0)) {
            searchDistance = optionsDistance * 16;
        }
        int renderDistance = Math.max(optionsDistance, (int) Math.ceil(searchDistance / 16));

        int camX = (int) viewport.cameraX;//Truncated, not floored: same as sodium's CameraTransform
        int camY = (int) viewport.cameraY;
        int camZ = (int) viewport.cameraZ;

        if (enabled) {
            int camSecX = ((int) Math.floor(viewport.cameraX)) >> 4;
            int camSecZ = ((int) Math.floor(viewport.cameraZ)) >> 4;
            if (this.needsRebuild
                    || this.gridSize != (renderDistance + 2 + RECENTER_SLACK) * 2 + 1
                    || Math.abs(camSecX - this.centreX) > RECENTER_SLACK
                    || Math.abs(camSecZ - this.centreZ) > RECENTER_SLACK) {
                this.rebuild(camSecX, camSecZ, renderDistance);
                viewport.vanillaSectionMask = this.maskBuffer;
            }

            if (this.dirtyMax >= this.dirtyMin) {
                int count = this.dirtyMax - this.dirtyMin + 1;
                long ptr = UploadStream.INSTANCE.upload(this.maskBuffer, this.dirtyMin * 4L, count * 4L);
                MemoryUtil.memIntBuffer(ptr, count).put(this.bits, this.dirtyMin, count);
                this.dirtyMin = Integer.MAX_VALUE;
                this.dirtyMax = -1;
            }
        }

        long ptr = UploadStream.INSTANCE.upload(viewport.vanillaMaskHeader, 0, HEADER_SIZE);
        MemoryUtil.memPutInt(ptr, camX);
        MemoryUtil.memPutInt(ptr + 4, camY);
        MemoryUtil.memPutInt(ptr + 8, camZ);
        MemoryUtil.memPutInt(ptr + 12, (enabled && !this.needsRebuild) ? 1 : 0);
        MemoryUtil.memPutFloat(ptr + 16, sodiumFractional(viewport.cameraX));
        MemoryUtil.memPutFloat(ptr + 20, sodiumFractional(viewport.cameraY));
        MemoryUtil.memPutFloat(ptr + 24, sodiumFractional(viewport.cameraZ));
        MemoryUtil.memPutFloat(ptr + 28, searchDistance);
        MemoryUtil.memPutInt(ptr + 32, this.originX);
        MemoryUtil.memPutInt(ptr + 36, this.originY);
        MemoryUtil.memPutInt(ptr + 40, this.originZ);
        MemoryUtil.memPutInt(ptr + 44, SODIUM_SECTION_PADDING);
        MemoryUtil.memPutInt(ptr + 48, this.gridSize);
        MemoryUtil.memPutInt(ptr + 52, this.sizeY);
        MemoryUtil.memPutInt(ptr + 56, 0);
        MemoryUtil.memPutInt(ptr + 60, 0);
        UploadStream.INSTANCE.commit();
    }

    //Same as sodium's CameraTransform.fractional
    private static float sodiumFractional(double value) {
        float fullPrecision = (float) (value - (int) value);
        float modifier = Math.copySign(128.0f, fullPrecision);
        return (fullPrecision + modifier) - modifier;
    }

    public void reset() {
        this.sections.clear();
        this.needsRebuild = true;
    }

    public void free() {
        this.maskBuffer.free();
    }
}
