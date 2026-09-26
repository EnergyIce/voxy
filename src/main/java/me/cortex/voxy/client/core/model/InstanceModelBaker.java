package me.cortex.voxy.client.core.model;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.voxelization.CamoDebug;
import me.cortex.voxy.common.voxelization.CamoStats;
import me.cortex.voxy.common.voxelization.InstanceKeyRegistry;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
//? if neoforge {
/*import net.neoforged.neoforge.client.model.data.ModelData;*/
//? } else if forge {
import net.minecraftforge.client.model.data.ModelData;
//?}
import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

//Bakes real, per-instance textures for camouflage/mimicry blocks (Framed Blocks, Create Copycat,
// Copycats+ - see CamouflageBlockCompat). These blocks don't use a BlockEntityRenderer for their camo
// appearance - e.g. Framed Blocks reads the camo BlockState out of Forge's ModelData system inside its
// own custom baked models (FramedCubeModel etc). So instead of live-rendering a BlockEntityRenderer,
// this reconstructs a detached BlockEntity purely to read its ModelData, then reuses the exact same
// software rasterization path (SoftwareModelTextureBakery) that Voxy already uses for every normal
// per-BlockState model - just parameterized with that instance's ModelData instead of ModelData.EMPTY.
// This means the block's real shape/geometry (panels, steps, ...) is preserved automatically, and the
// real camo texture is captured with it, all through the existing, already-correct baking pipeline.
//
// Writes into the SAME shared model buffer/texture atlas that ModelFactory's normal per-BlockState
// models use (see ModelStore) - just in a reserved sub-range of the 16-bit model id space, so no
// shader changes are needed; the section mesher just needs to pick the right id for a given voxel
// (see Mapper#hasInstanceOverride / RenderDataFactory).
//
// Intentionally simple lifecycle: this cache is session-scoped (rebuilt every launch, like
// ModelFactory's own blockId->modelId cache already is) and not persisted. Bake requests that can't
// be resolved (nothing rendered, e.g. an empty/uncamouflaged instance, or the reserved id range is
// exhausted) are dropped - the affected voxel just keeps showing the wrapper block's plain
// per-BlockState appearance, which is always a safe, existing fallback (never a crash or garbage).
public class InstanceModelBaker {
    private static final int SIZE = ModelFactory.MODEL_TEXTURE_SIZE;
    //Top of the 16-bit model id space (see ModelStore/quad_format.glsl - model id is a 16 bit field)
    // reserved for instance models, leaving the rest to ModelFactory's normal per-BlockState models.
    private static final int RESERVED_COUNT = 8192;
    private static final int FIRST_RESERVED_ID = (1 << 16) - RESERVED_COUNT;

    //Budget for how many instance bakes to attempt per frame.
    public static final int DEFAULT_BUDGET_PER_FRAME = 4;

    private final ModelFactory modelFactory;

    //Own SoftwareModelTextureBakery instance (own VertexConsumers/rasterizer state) so instance bakes
    // never race with ModelFactory's own bakery2, which is driven concurrently from the separate
    // "Model factory processor" thread.
    private final SoftwareModelTextureBakery bakery = new SoftwareModelTextureBakery();
    private final long scratchBuffer = MemoryUtil.nmemAlloc((long) SIZE * SIZE * 8 * 6);
    //Opaque-only / translucent-only renderings of the same model (see the bakery's renderToOutputWithModelData)
    private final long scratchOpaque = MemoryUtil.nmemAlloc((long) SIZE * SIZE * 8 * 6);
    private final long scratchTranslucent = MemoryUtil.nmemAlloc((long) SIZE * SIZE * 8 * 6);

    //instanceIndex -> modelId. Absent = never requested. -1 = requested but couldn't be baked
    // (permanently, e.g. nothing rendered) - don't retry. >=0 = baked and ready.
    private final Int2IntOpenHashMap resolved = new Int2IntOpenHashMap();
    private final IntOpenHashSet requested = new IntOpenHashSet();
    private final ConcurrentLinkedQueue<Integer> pending = new ConcurrentLinkedQueue<>();
    //resolved/requested are plain fastutil collections (not thread-safe). getModelId/isPending are
    // read from many "Chunk Render Task Executor" mesh-building worker threads concurrently, while
    // tick()/bakeOne() write to them from the render thread - unsynchronized access let a put() on
    // the render thread rehash resolved's backing array mid-read on a worker thread, corrupting it
    // and throwing ArrayIndexOutOfBoundsException out of Int2IntOpenHashMap.get(). That exception
    // then escaped RenderGenerationService.processJob() uncaught, leaking the in-flight WorldSection
    // forever (see the try/finally fix there) and hanging shutdown. All access to resolved/requested
    // must go through this lock.
    private final Object lock = new Object();
    //instanceIndex -> ids of the extra "plane" models of a stepped/layered instance (see
    // ModelPlaneSplitter). The primary plane is the normal resolved id; the mesher emits an extra quad
    // per secondary id. Guarded by lock like resolved.
    private final java.util.HashMap<Integer, int[]> secondary = new java.util.HashMap<>();

    private int nextModelId = FIRST_RESERVED_ID;
    private boolean loggedExhausted = false;
    //TEMP diagnostic: caps verbose per-bake logging so a short test session doesn't flood the log.
    private static final AtomicInteger DEBUG_LOG_BUDGET = new AtomicInteger(30);

    //Base class of every Create (and Create addon, incl. Copycats+) block entity; its handleUpdateTag reads the same
    // keys as its disk format (see bakeOne). Null when Create isn't installed.
    private static final Class<?> CREATE_SYNCED_BE;
    static {
        Class<?> c = null;
        try {
            c = Class.forName("com.simibubi.create.foundation.blockEntity.SyncedBlockEntity");
        } catch (Throwable t) {
            //Create not installed
        }
        CREATE_SYNCED_BE = c;
    }

    public InstanceModelBaker(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
        this.resolved.defaultReturnValue(-2);//-2 = "no entry" (distinct from -1 "permanently failed")
        this.bakery.setupTexture();
    }

    //Callable from any thread (section mesh generation workers). Returns -1 if not ready/available
    // yet (caller should fall back to the wrapper block's normal model), and enqueues a bake attempt
    // the first time a given instance index is seen.
    public int getModelId(int instanceIndex) {
        synchronized (this.lock) {
            int existing = this.resolved.get(instanceIndex);
            if (existing != -2) {
                return Math.max(existing, -1);
            }
            if (this.requested.add(instanceIndex)) {
                this.pending.add(instanceIndex);
            }
            return -1;
        }
    }

    //True if this instance was requested (via getModelId) but hasn't reached a terminal result yet -
    // i.e. it's still queued/being baked, as opposed to a *permanent* failure (nothing to bake, e.g.
    // no camo quads, a stale index, or the id space being exhausted - see bakeOne()). Callers should
    // use this to decide whether a -1 from getModelId() is worth retrying later (re-mesh once baked)
    // versus treating it as a stable, permanent fallback to the plain per-BlockState model.
    public boolean isPending(int instanceIndex) {
        synchronized (this.lock) {
            return this.resolved.get(instanceIndex) == -2;
        }
    }

    //Extra plane-model ids for a resolved instance, or null if it is a single plane. Only meaningful once
    // getModelId() returned a valid id (secondaries are registered before the primary is marked resolved).
    public int[] getSecondaryModelIds(int instanceIndex) {
        synchronized (this.lock) {
            return this.secondary.get(instanceIndex);
        }
    }

    private void setSecondary(int instanceIndex, int[] ids) {
        synchronized (this.lock) {
            this.secondary.put(instanceIndex, ids);
        }
    }

    //All writes to resolved must go through here, so they share the same monitor as the reads above.
    private void setResolved(int instanceIndex, int modelId) {
        synchronized (this.lock) {
            this.resolved.put(instanceIndex, modelId);
        }
    }

    //No longer needs a valid GL context/render thread - the bake itself is pure CPU (software
    // rasterization against an already-cached texture atlas snapshot), same as ModelFactory's normal
    // per-BlockState baking. Kept as a budgeted tick (instead of draining fully) to bound per-frame cost.
    //Time-budgeted rather than count-budgeted: a fixed handful per frame left big camo builds popping in
    // for minutes. At least one bake per tick, then keep going until ~3ms of the frame is used.
    private static final long TICK_BUDGET_NANOS = 3_000_000L;
    private long lastStatsLog = System.currentTimeMillis();

    public void tick(WorldEngine world, int minBudget) {
        if (!CamoDebug.ENABLED) return;
        long start = System.nanoTime();
        for (int i = 0; ; i++) {
            if (i >= minBudget && System.nanoTime() - start > TICK_BUDGET_NANOS) break;
            Integer instanceIndex = this.pending.poll();
            if (instanceIndex == null) break;
            try {
                this.bakeOne(world, instanceIndex);
            } catch (Exception e) {
                Logger.error("Voxy: failed to bake camouflage instance " + instanceIndex, e);
                CamoStats.count(this.blockName(world, instanceIndex), "bake.exception");
                this.setResolved(instanceIndex, -1);
            }
        }
        long now = System.currentTimeMillis();
        if (now - this.lastStatsLog >= 20_000) {
            this.lastStatsLog = now;
            String report = CamoStats.reportIfChanged();
            if (report != null) {
                Logger.info("Voxy camo stats (per block: events):" + report);
            }
        }
    }

    //TEMP diagnostic: writes the 6 baked face textures of an instance (top: colour with alpha forced
    // opaque, bottom: alpha as greyscale) as one enlarged PNG sheet to <gamedir>/voxy_camo_dump/, so
    // corrupted/garbled bakes can be inspected directly. Bounded by DUMP_BUDGET.
    private static final AtomicInteger DUMP_BUDGET = new AtomicInteger(300);

    private void dumpBake(int instanceIndex, String blockName, ColourDepthTextureData[] textureData) {
        try {
            final int scale = 8;
            var img = new java.awt.image.BufferedImage(6 * SIZE * scale, 3 * SIZE * scale, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int face = 0; face < 6; face++) {
                var c = textureData[face].colour();
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        int abgr = c[y * SIZE + x];
                        int a = (abgr >>> 24) & 0xFF;
                        int argbOpaque = 0xFF000000 | ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
                        int argbAlpha = 0xFF000000 | (a << 16) | (a << 8) | a;
                        //Third row: depth of written pixels (dark = near the face, bright = deep), black if unwritten
                        int dword = textureData[face].depth()[y * SIZE + x];
                        int dv = (dword & 0xFF) != 0 ? Math.min(255, (int) (((dword >>> 8) / (double) ((1 << 24) - 1)) * 255.0 * 2.0)) : 0;
                        int argbDepth = 0xFF000000 | (dv << 16) | (dv << 8) | dv;
                        for (int sy = 0; sy < scale; sy++) {
                            for (int sx = 0; sx < scale; sx++) {
                                int px = (face * SIZE + x) * scale + sx;
                                img.setRGB(px, y * scale + sy, argbOpaque);
                                img.setRGB(px, (SIZE + y) * scale + sy, argbAlpha);
                                img.setRGB(px, (2 * SIZE + y) * scale + sy, argbDepth);
                            }
                        }
                    }
                }
            }
            var dir = Minecraft.getInstance().gameDirectory.toPath().resolve("voxy_camo_dump");
            java.nio.file.Files.createDirectories(dir);
            var file = dir.resolve(instanceIndex + "_" + blockName.replace(':', '_') + ".png");
            javax.imageio.ImageIO.write(img, "png", file.toFile());
        } catch (Throwable t) {
            Logger.error("Voxy: failed to dump camo bake " + instanceIndex, t);
        }
    }

    private static ColourDepthTextureData[] readFaces(long ptr) {
        var textureData = new ColourDepthTextureData[6];
        final int FACE_SIZE = SIZE * SIZE;
        for (int face = 0; face < 6; face++) {
            long faceDataPtr = ptr + (long) (FACE_SIZE * 4) * face * 2;
            int[] colour = new int[FACE_SIZE];
            int[] depth = new int[FACE_SIZE];
            for (int i = 0; i < FACE_SIZE; i++) {
                long value = MemoryUtil.memGetLong(faceDataPtr + (long) i * 8);
                colour[i] = (int) value;
                depth[i] = (int) (value >>> 32);
            }
            textureData[face] = new ColourDepthTextureData(colour, depth, SIZE, SIZE);
        }
        return textureData;
    }

    //Layer of a purely opaque-pass model: solid if every drawn pixel is fully opaque, else cutout
    private static RenderType chooseOpaqueLayer(ColourDepthTextureData[] textureData, boolean isLeaves) {
        if (isLeaves) return RenderType.solid();
        boolean solid = true;
        for (var face : textureData) {
            solid &= TextureUtils.isSolidWhereDrawn(face);
            if (!solid) break;
        }
        return solid ? RenderType.solid() : RenderType.cutout();
    }

    //Same layer decision the single-model bake always made, factored out so each plane model can be classified
    // from its own pixels (a glass plane and a brick plane of one block end up in different layers).
    private static RenderType chooseLayer(ColourDepthTextureData[] textureData, boolean anyTranslucentQuads, boolean anyDiscardQuads, boolean isLeaves) {
        RenderType layer = null;
        if (anyTranslucentQuads) {
            boolean anyTranslucent = false;
            for (var face : textureData) {
                anyTranslucent |= TextureUtils.hasTranslucentPixel(face);
                if (anyTranslucent) break;
            }
            if (anyTranslucent) {
                layer = RenderType.translucent();
            } else {
                boolean solid = true;
                for (var face : textureData) {
                    solid &= TextureUtils.isSolidWhereDrawn(face);
                    if (!solid) break;
                }
                layer = solid ? RenderType.solid() : RenderType.cutout();
            }
        }
        if (layer == null && anyDiscardQuads) {
            layer = RenderType.cutout();
        }
        if (isLeaves) {
            layer = RenderType.solid();
        }
        if (layer == null) {
            layer = RenderType.solid();
        }
        return layer;
    }

    private String blockName(WorldEngine world, int instanceIndex) {
        var key = world.getInstanceKeyRegistry().getKey(instanceIndex);
        return key == null ? "<unknown-index>" : String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(key.wrapperState().getBlock()));
    }

    private void bakeOne(WorldEngine world, int instanceIndex) {
        var key = world.getInstanceKeyRegistry().getKey(instanceIndex);
        if (key == null) {
            //Stale index (its persisted registry entry is missing/unreadable) - nothing we can rebuild it from
            CamoStats.count("<unknown-index>", "bake.unknownIndex");
            this.setResolved(instanceIndex, -1);
            return;
        }

        final String blockName = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(key.wrapperState().getBlock()));
        if (this.nextModelId >= (1 << 16)) {
            CamoStats.count(blockName, "bake.exhausted");
            if (!this.loggedExhausted) {
                this.loggedExhausted = true;
                Logger.warn("Voxy: camouflage instance model capacity (" + RESERVED_COUNT + ") exhausted this session, further instances will show their plain block appearance");
            }
            this.setResolved(instanceIndex, -1);
            return;
        }

        boolean debugLog = CamoDebug.DIAGNOSTICS && DEBUG_LOG_BUDGET.getAndDecrement() > 0;

        //? if neoforge {
        /*//NeoForge doesn't need instance ModelData for camouflage blocks the same way (kept out of
        // scope for now) - fall back to plain per-BlockState behaviour.
        this.setResolved(instanceIndex, -1);
        return;*/
        //? } else if forge {
        BlockEntity be = null;
        try {
            //The stored NBT is the DISK format (BlockEntity#saveWithId), so BlockEntity.loadStatic (load) is the
            // matching reader and is used for everything - except Create-based block entities (Create Copycat,
            // Copycats+ and other Create addons). Their disk read (read(tag, clientPacket=false)) validates the
            // stored material against the consumed item and silently resets it to the bare copycat base when the
            // item is empty/unmatched (blocks placed by command, schematic or another mod), which the live
            // client never does. For them the client path (handleUpdateTag -> read(tag, clientPacket=true))
            // is used, which reads the exact same keys. It must NOT be used for other mods: e.g. Framed Blocks'
            // handleUpdateTag expects its NETWORK format (numeric camo type ids) and drops the disk-format camo,
            // baking the empty frame texture.
            if (CREATE_SYNCED_BE != null && key.wrapperState().getBlock() instanceof EntityBlock entityBlock) {
                be = entityBlock.newBlockEntity(BlockPos.ZERO, key.wrapperState());
                if (be != null) {
                    if (CREATE_SYNCED_BE.isInstance(be)) {
                        be.handleUpdateTag(key.nbt());
                    } else {
                        be = null;//Not what we expected - use the generic disk path below
                    }
                }
            }
        } catch (Exception e) {
            Logger.error("Voxy: could not apply client update data to camouflage BlockEntity, falling back to loadStatic", e);
            be = null;
        }
        if (be == null) {
            try {
                be = BlockEntity.loadStatic(BlockPos.ZERO, key.wrapperState(), key.nbt());
            } catch (Exception e) {
                Logger.error("Voxy: could not reconstruct BlockEntity for camouflage instance bake", e);
                this.setResolved(instanceIndex, -1);
                return;
            }
        }
        if (be == null) {
            CamoStats.count(blockName, "bake.noBE");
            if (debugLog) Logger.info("Voxy camo bake DEBUG: instance=" + instanceIndex + " state=" + key.wrapperState() + " -> loadStatic returned null, falling back");
            this.setResolved(instanceIndex, -1);
            return;
        }
        be.setLevel(Minecraft.getInstance().level);

        ModelData modelData;
        try {
            modelData = be.getModelData();
        } catch (Exception e) {
            modelData = ModelData.EMPTY;
        }

        int flags = this.bakery.renderToOutputWithModelData(key.wrapperState(), this.scratchBuffer, modelData, be, this.scratchOpaque, this.scratchTranslucent);

        if ((flags & 16) == 0) {
            //Genuinely no quads produced (e.g. empty/uncamouflaged state, or this block doesn't use
            // ModelData for its camo appearance) - fall back to the plain block forever
            CamoStats.count(blockName, "bake.noQuads");
            if (CamoStats.shouldDetail(blockName, "noQuads")) {
                Logger.warn("Voxy camo bake: NO QUADS for " + key.wrapperState() + " nbt=" + key.nbt());
            }
            if (debugLog) Logger.info("Voxy camo bake DEBUG: instance=" + instanceIndex + " state=" + key.wrapperState() + " -> no quads produced, falling back");
            this.setResolved(instanceIndex, -1);
            return;
        }

        var textureData = readFaces(this.scratchBuffer);
        if (debugLog) Logger.info("Voxy camo bake DEBUG: instance=" + instanceIndex + " state=" + key.wrapperState() + " -> got quads, flags=" + flags);
        if (CamoStats.shouldDetail(blockName, "dump") && DUMP_BUDGET.getAndDecrement() > 0) {
            this.dumpBake(instanceIndex, blockName, textureData);
        }

        boolean isShaded = (flags & 1) != 0;
        boolean hasDarkenedTextures = (flags & 2) != 0;
        boolean anyTranslucentQuads = (flags & 4) != 0;
        boolean anyDiscardQuads = (flags & 8) != 0;
        boolean isLeaves = key.wrapperState().is(BlockTags.LEAVES);

        int modelId;
        //A block that is (by Voxy's own criteria) a solid opaque cube must stay ONE model: Voxy takes the light of a
        // fully opaque block from its neighbour (its own cell light is 0), while any non-opaque model uses its own
        // cell light - splitting such a block into depth planes makes each plane "not fully opaque" and turns
        // e.g. solid walls/plinths with a little surface relief dark.
        final boolean solidCube = ModelFactory.instanceIsFullyOpaque(textureData, chooseLayer(textureData, anyTranslucentQuads, anyDiscardQuads, isLeaves));
        boolean mixed = CamoDebug.MIXED_LAYERS && !solidCube && anyTranslucentQuads && (flags & 32) != 0;
        if (mixed) {
            //Glass (translucent) AND opaque geometry in one block: keep them as independent layers instead of
            // the blended composite, so e.g. a brick slab stays behind its glass plate with its own depth and
            // the GPU blends them at their real positions.
            var opaqueTex = readFaces(this.scratchOpaque);
            var translTex = readFaces(this.scratchTranslucent);
            var oPlanes = ModelPlaneSplitter.split(opaqueTex, 3);
            var tPlanes = ModelPlaneSplitter.split(translTex, 2, false);
            if (oPlanes == null) oPlanes = new ColourDepthTextureData[][]{opaqueTex};
            if (tPlanes == null) tPlanes = new ColourDepthTextureData[][]{translTex};
            int total = oPlanes.length + tPlanes.length;
            if (this.nextModelId + total <= (1 << 16)) {
                int doubleSided = ModelFactory.instanceNeedsDoubleSided(textureData, RenderType.translucent()) ? 1 : 0;
                int fullyOpaque = -1;
                int[] ids = new int[total];
                int n = 0;
                for (var tex : oPlanes) {
                    ids[n] = this.nextModelId++;
                    this.modelFactory.enqueueUpload(this.modelFactory.buildInstanceModelUpload(ids[n], key.wrapperState(), tex, isShaded, hasDarkenedTextures, chooseOpaqueLayer(tex, isLeaves), doubleSided, fullyOpaque));
                    n++;
                }
                for (var tex : tPlanes) {
                    ids[n] = this.nextModelId++;
                    this.modelFactory.enqueueUpload(this.modelFactory.buildInstanceModelUpload(ids[n], key.wrapperState(), tex, isShaded, hasDarkenedTextures, RenderType.translucent(), doubleSided, -1));
                    n++;
                }
                if (CamoStats.shouldDetail(blockName, "dumpPlane") && DUMP_BUDGET.get() > 0) {
                    for (int k = 0; k < total; k++) {
                        DUMP_BUDGET.decrementAndGet();
                        this.dumpBake(instanceIndex, blockName + "_layer" + k + (k < oPlanes.length ? "opaque" : "translucent"), k < oPlanes.length ? oPlanes[k] : tPlanes[k - oPlanes.length]);
                    }
                }
                this.setSecondary(instanceIndex, java.util.Arrays.copyOfRange(ids, 1, ids.length));
                modelId = ids[0];
                CamoStats.count(blockName, "bake.multiLayer");
                this.setResolved(instanceIndex, modelId);
                CamoStats.count(blockName, "bake.ok");
                return;
            }
        }
        var planes = (CamoDebug.MULTI_PLANE && !solidCube) ? ModelPlaneSplitter.split(textureData) : null;
        if (planes != null && this.nextModelId + planes.length <= (1 << 16)) {
            //Stepped/layered model: one Voxy model per depth plane (nearest first), primary + secondaries
            int[] ids = new int[planes.length];
            //Double-sidedness is a property of the whole block, not of one plane (see instanceNeedsDoubleSided)
            int doubleSided = ModelFactory.instanceNeedsDoubleSided(textureData, chooseLayer(textureData, anyTranslucentQuads, anyDiscardQuads, isLeaves)) ? 1 : 0;
            int fullyOpaque = -1;
            for (int plane = 0; plane < planes.length; plane++) {
                ids[plane] = this.nextModelId++;
                var layer = chooseLayer(planes[plane], anyTranslucentQuads, anyDiscardQuads, isLeaves);
                this.modelFactory.enqueueUpload(this.modelFactory.buildInstanceModelUpload(ids[plane], key.wrapperState(), planes[plane], isShaded, hasDarkenedTextures, layer, doubleSided, fullyOpaque));
            }
            if (CamoStats.shouldDetail(blockName, "dumpPlane") && DUMP_BUDGET.get() > 0) {
                for (int plane = 0; plane < planes.length; plane++) {
                    DUMP_BUDGET.decrementAndGet();
                    this.dumpBake(instanceIndex, blockName + "_plane" + plane, planes[plane]);
                    var sb = new StringBuilder();
                    for (int face = 0; face < 6; face++) {
                        int written = TextureUtils.getWrittenPixelCount(planes[plane][face], TextureUtils.WRITE_CHECK_STENCIL);
                        float depth = written == 0 ? -1 : TextureUtils.computeDepth(planes[plane][face], TextureUtils.DEPTH_MODE_AVG, TextureUtils.WRITE_CHECK_STENCIL);
                        var bounds = written == 0 ? new int[]{-1, -1, -1, -1} : TextureUtils.computeBounds(planes[plane][face], TextureUtils.WRITE_CHECK_STENCIL);
                        sb.append(String.format(" f%d[n=%d d=%.2f b=%s]", face, written, depth, java.util.Arrays.toString(bounds)));
                    }
                    Logger.info("Voxy camo planes: " + key.wrapperState() + " instance=" + instanceIndex + " plane" + plane + sb);
                }
            }
            //Registered before the primary is marked resolved so the mesher never sees a primary without them
            this.setSecondary(instanceIndex, java.util.Arrays.copyOfRange(ids, 1, ids.length));
            modelId = ids[0];
            CamoStats.count(blockName, "bake.multiPlane");
        } else {
            modelId = this.nextModelId++;
            var layer = chooseLayer(textureData, anyTranslucentQuads, anyDiscardQuads, isLeaves);
            this.modelFactory.enqueueUpload(this.modelFactory.buildInstanceModelUpload(modelId, key.wrapperState(), textureData, isShaded, hasDarkenedTextures, layer));
        }
        this.setResolved(instanceIndex, modelId);
        CamoStats.count(blockName, "bake.ok");
        if (CamoStats.shouldDetail(blockName, "ok")) {
            var bb = this.bakery.dbgBounds;
            Logger.info("Voxy camo bake OK: " + key.wrapperState() + " quads=" + this.bakery.dbgQuadCount
                    + String.format(" bounds=[%.2f,%.2f,%.2f]-[%.2f,%.2f,%.2f]", bb[0], bb[1], bb[2], bb[3], bb[4], bb[5])
                    + " flags=" + flags + " nbt=" + key.nbt());
        }
        if (debugLog) Logger.info("Voxy camo bake DEBUG: instance=" + instanceIndex + " -> baked OK, modelId=" + modelId);
        //? } else {
        /*//Fabric doesn't have Forge's ModelData system - no way to recover the camo texture here yet,
        // fall back to plain per-BlockState behaviour.
        this.setResolved(instanceIndex, -1);*/
        //?}
    }

    public void free() {
        this.bakery.free();
        MemoryUtil.nmemFree(this.scratchBuffer);
        MemoryUtil.nmemFree(this.scratchOpaque);
        MemoryUtil.nmemFree(this.scratchTranslucent);
    }
}
