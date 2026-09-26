package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.UnsafeUtil;
//? if neoforge {
/*import net.neoforged.neoforge.client.model.data.ModelData;*/
//? } else if forge {
import net.minecraftforge.client.model.data.ModelData;
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureImage;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

public class SoftwareModelTextureBakery {
    // Note: the first bit of metadata is if alpha discard is enabled
    //Public so InstanceModelBaker (real GL rendering of BlockEntities) can reuse the exact same
    // 6 view/projection matrices, so its output aligns pixel-for-pixel with this software-rasterized
    // per-BlockState output in the shared atlas.
    public static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final ReuseVertexConsumer opaqueVC = new ReuseVertexConsumer();
    private final ReuseVertexConsumer translucentVC = new ReuseVertexConsumer(1/*has discard*/);
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer(ModelFactory.MODEL_TEXTURE_SIZE);


    public SoftwareModelTextureBakery() {
    }

    public void setupTexture() {
        var texture = Minecraft.getInstance().getTextureManager().getTexture(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"));

        int textureId = texture.getId();

        if (!RenderSystem.isOnRenderThread()) {
            CompletableFuture<Void> future = new CompletableFuture<>();

            RenderSystem.recordRenderCall(() -> {
                try {
                    _doSetupTexture(textureId);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            future.join();
        } else {
            _doSetupTexture(textureId);
        }
    }

    private void _doSetupTexture(int glId) {
        glBindTexture(GL_TEXTURE_2D, glId);
        int width = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
        int height = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);

        int[] pixels = new int[width * height];
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

        this.rasterizer.setSamplerTexture(pixels, width, height);
    }

    private void bakeBlockModel(BlockState state, RenderType layer) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;// Dont bake if invisible
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        for (Direction direction : new Direction[] { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, null }) {
            List<BakedQuad> quads;
            try {
                //Some third-party mods' BakedModel implementations assume they're only ever queried
                // from the normal chunk-render pipeline (e.g. relying on a Guava LoadingCache whose
                // loader needs render context that isn't available here) and can throw when baked
                // headlessly like this. One bad BlockState's model must not take down the whole
                // "Model factory processor" thread (which is fatal - see ModelBakerySubsystem#tick),
                // so treat a throw here the same as that direction legitimately having no quads.
                quads = model.getQuads(state, direction, new SingleThreadedRandomSource(42L));
            } catch (Exception e) {
                Logger.error("Voxy: a block model threw while baking " + state + " (face " + direction + "), skipping that face", e);
                continue;
            }
            for (var quad : quads) {
                (layer == RenderType.translucent() ? this.translucentVC : this.opaqueVC)
                        .quad(quad, state.is(BlockTags.LEAVES), layer);
            }
        }
    }

    //? if forge || neoforge {
    private void bakeBlockModelWithData(BlockState state, RenderType layer, ModelData modelData) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;// Dont bake if invisible
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        for (Direction direction : new Direction[] { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, null }) {
            List<BakedQuad> quads;
            try {
                //See the equivalent try/catch in bakeBlockModel() above for why this is needed.
                quads = model.getQuads(state, direction, new SingleThreadedRandomSource(42L), modelData, layer);
            } catch (Exception e) {
                Logger.error("Voxy: a block model threw while baking instance appearance for " + state + " (face " + direction + "), skipping that face", e);
                continue;
            }
            for (var quad : quads) {
                (layer == RenderType.translucent() ? this.translucentVC : this.opaqueVC)
                        .quad(quad, state.is(BlockTags.LEAVES), layer);
            }
        }
    }

    //Sibling of renderToOutput() for instance-specific bakes (camouflage/mimicry blocks like Framed
    // Blocks/Create Copycat) that need per-instance ModelData - e.g. Framed Blocks' custom baked models
    // (FramedCubeModel etc.) read the camo BlockState out of ModelData rather than using a
    // BlockEntityRenderer, so plain per-BlockState baking (ModelData.EMPTY) always yields the bare/
    // uncamouflaged appearance. Solid-block only (no fluids), which covers every known camo block.
    public int renderToOutputWithModelData(BlockState state, long outputBuffer, ModelData modelData) {
        return this.renderToOutputWithModelData(state, outputBuffer, modelData, null);
    }

    //Minimal single-block "world" for BakedModel#getModelData(): the real block state + block entity
    // at ZERO, air everywhere else, so models that gather neighbour/occlusion/material data from the
    // level (Copycats+, Framed Blocks) see a self-consistent isolated block instead of whatever
    // happens to be at the world origin.
    private static final class IsolatedBlockGetter implements BlockAndTintGetter {
        private final BlockState state;
        private final BlockEntity blockEntity;

        private IsolatedBlockGetter(BlockState state, BlockEntity blockEntity) {
            this.state = state;
            this.blockEntity = blockEntity;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            var level = Minecraft.getInstance().level;
            return level == null ? null : level.getLightEngine();
        }

        @Override
        public int getBrightness(LightLayer type, BlockPos pos) {
            return type == LightLayer.SKY ? 15 : 0;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return -1;
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return pos.equals(BlockPos.ZERO) ? this.blockEntity : null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos.equals(BlockPos.ZERO) ? this.state : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 16;
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }

        @Override
        public float getShade(Direction direction, boolean bl) {
            return 1.0f;
        }
    }

    //blockEntity may be null. When given, the model gets to build its own ModelData from it - many
    // camo/copycat models (Copycats+ "MATERIALS"/"OCCLUSION"/"WRAPPED_DATA", Framed Blocks' neighbour
    // data) are assembled in BakedModel#getModelData(level, pos, state, beData), NOT in the block
    // entity, and return no quads at all without it. It also bakes every render layer the model
    // reports (a copycat wrapping glass/leaves lives in translucent/cutout, not the wrapper's own layer).
    public int renderToOutputWithModelData(BlockState state, long outputBuffer, ModelData modelData, @Nullable BlockEntity blockEntity) {
        return this.renderToOutputWithModelData(state, outputBuffer, modelData, blockEntity, 0, 0);
    }

    //opaqueOut / translucentOut (0 = skip): additionally receive the opaque-only and the translucent-only
    // renderings of the same model, same layout as outputBuffer. The composite in outputBuffer blends
    // translucent surfaces over the opaque ones behind them, which destroys the depth of what lies behind
    // (glass in front of bricks becomes one flush "brick-tinted glass" pixel); the separate layers let the
    // caller keep glass and bricks as independent planes. Extra return bit 32: the model has opaque quads.
    public int renderToOutputWithModelData(BlockState state, long outputBuffer, ModelData modelData, @Nullable BlockEntity blockEntity, long opaqueOut, long translucentOut) {
        MemoryUtil.memSet(outputBuffer, 0, 16 * 16 * 8 * 6);
        if (opaqueOut != 0) MemoryUtil.memSet(opaqueOut, 0, 16 * 16 * 8 * 6);
        if (translucentOut != 0) MemoryUtil.memSet(translucentOut, 0, 16 * 16 * 8 * 6);

        this.opaqueVC.reset();
        this.translucentVC.reset();

        var model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
        if (blockEntity != null) {
            try {
                modelData = model.getModelData(new IsolatedBlockGetter(state, blockEntity), BlockPos.ZERO, state, modelData);
            } catch (Exception e) {
                Logger.error("Voxy: a block model threw while gathering model data for " + state + ", baking with the block entity's own data only", e);
            }
        }

        List<RenderType> layers = new ArrayList<>();
        try {
            for (var rt : model.getRenderTypes(state, new SingleThreadedRandomSource(42L), modelData)) {
                layers.add(rt);
            }
        } catch (Exception e) {
            Logger.error("Voxy: a block model threw while listing render types for " + state, e);
        }
        if (layers.isEmpty()) {
            layers.add(ItemBlockRenderTypes.getChunkRenderType(state));
        }
        boolean isLeaves = state.getBlock() instanceof LeavesBlock;
        for (var layer : layers) {
            this.bakeBlockModelWithData(state, isLeaves ? RenderType.solid() : layer, modelData);
        }
        boolean isAnyShaded = this.opaqueVC.anyShaded | this.translucentVC.anyShaded;
        boolean isAnyDarkend = this.opaqueVC.anyDarkendTex | this.translucentVC.anyDarkendTex;
        boolean anyTranslucent = !this.translucentVC.isEmpty();
        boolean anyDiscard = this.opaqueVC.anyDiscard;
        //Same "is there actually anything to rasterize" gate the normal per-BlockState path uses -
        // exposed via bit 4 so callers (InstanceModelBaker) can tell "genuinely empty model" apart
        // from "model has content" without having to reverse-engineer the rasterizer's pixel format.
        boolean hadAnyQuads = !(this.opaqueVC.isEmpty() && this.translucentVC.isEmpty());
        if (hadAnyQuads) {
            for (int i = 0; i < VIEWS.length; i++) {
                boolean cull = i == 1 || i == 2 || i == 4;
                this.rasterizer.clear();
                //Two-sided: what a face texture must capture is the nearest surface seen from that direction,
                // independent of triangle winding. The single-sided vanilla setup silently drops one winding
                // per view, which for composite camo models (parts at different depths, mirrored/rotated
                // pieces) showed the wrong side's surfaces on the wrong face. The conventional
                // (front-facing) orientation is drawn first so that strict depth testing keeps translucent
                // surfaces from being blended twice by their own back faces.
                this.rasterizer.setBlending(false);
                this.rasterizer.setFaceCull(cull);
                this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                this.rasterizer.setFaceCull(!cull);
                this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                this.rasterizer.setBlending(true);
                this.rasterizer.setFaceCull(cull);
                this.rasterizer.raster(VIEWS[i], this.translucentVC);
                this.rasterizer.setFaceCull(!cull);
                this.rasterizer.raster(VIEWS[i], this.translucentVC);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(),
                        outputBuffer + (SINGLE_FACE_OUTPUT_SIZE * i));

                if (opaqueOut != 0) {
                    this.rasterizer.clear();
                    this.rasterizer.setBlending(false);
                    this.rasterizer.setFaceCull(cull);
                    this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                    this.rasterizer.setFaceCull(!cull);
                    this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), opaqueOut + (SINGLE_FACE_OUTPUT_SIZE * i));
                }
                if (translucentOut != 0) {
                    if (opaqueOut != 0) {
                        //Depth test the translucent layer against the opaque layer that was just rasterised
                        // (keep only the depth bits, drop colour and stencil): translucent surfaces BEHIND
                        // opaque ones are hidden and must not end up in the translucent texture. That removes
                        // internal faces between parts of one block (e.g. the face where a glass part touches a
                        // brick part) which the game never draws because its neighbour part hides them.
                        var fb = this.rasterizer.getRawFramebuffer();
                        for (int px = 0; px < fb.length; px++) {
                            fb[px] &= 0xFFFFFF0000000000L;
                        }
                    } else {
                        this.rasterizer.clear();
                    }
                    this.rasterizer.setBlending(true);
                    this.rasterizer.setFaceCull(cull);
                    this.rasterizer.raster(VIEWS[i], this.translucentVC);
                    this.rasterizer.setFaceCull(!cull);
                    this.rasterizer.raster(VIEWS[i], this.translucentVC);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), translucentOut + (SINGLE_FACE_OUTPUT_SIZE * i));
                }
            }
        }

        boolean anyOpaque = !this.opaqueVC.isEmpty();
        return (isAnyShaded ? 1 : 0) | (isAnyDarkend ? 2 : 0) | (anyTranslucent ? 4 : 0) | (anyDiscard ? 8 : 0) | (hadAnyQuads ? 16 : 0) | (anyOpaque ? 32 : 0);
    }
    //?}

    private void bakeFluidState(BlockState state, int face, RenderType layer) {
        BlockAndTintGetter getter = new BlockAndTintGetter() {
            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }
            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                //This is such a stupid and bad hack, we can inject tinting state here since this is called
                // before the quad is added
                //TODO: need to make a quad once tinting thing
                translucentVC.setDefaultMeta(translucentVC.getDefaultMeta()|4);//Tinting
                opaqueVC.setDefaultMeta(opaqueVC.getDefaultMeta()|4);//Tinting
                return -1;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }

                //Fixme:
                // This makes it so that the top face of water is always air, if this is commented out
                //  the up block will be a liquid state which makes the sides full
                // if this is uncommented, that issue is fixed but e.g. stacking water layers ontop of eachother
                //  doesnt fill the side of the block

                //if (pos.getY() == 1) {
                //    return Blocks.AIR.getDefaultState();
                //}
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }

                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }

            @Override
            public float getShade(Direction direction, boolean bl) {
                return 0;
            }
        
        };
        
        VertexConsumer vc = this.opaqueVC;;

        if (layer == RenderType.translucent()) vc = this.translucentVC;
        if (layer == RenderType.cutout()) {
            this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta()|1);//set discard
        } else {
            this.opaqueVC.setDefaultMeta(this.opaqueVC.getDefaultMeta()&~1);//remove discard
        }
        Minecraft.getInstance().getBlockRenderer().renderLiquid(BlockPos.ZERO, getter, vc, state, state.getFluidState());
        this.translucentVC.setDefaultMeta(0);//Reset default meta
        this.opaqueVC.setDefaultMeta(0);//Reset default meta
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getNormal();
        int dot = fv.getX() * pos.getX() + fv.getY() * pos.getY() + fv.getZ() * pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.opaqueVC.free();
        this.translucentVC.free();
    }

    private static final long SINGLE_FACE_OUTPUT_SIZE = (ModelFactory.MODEL_TEXTURE_SIZE
            * ModelFactory.MODEL_TEXTURE_SIZE) * 8;
    // The outputBuffer layout is different from the non software rasterized
    // ModelTextureBakery
    // in this version the values are simply appended
    // (0,0),(1,0),(2,0),(0,1),(1,1),(2,1)

    public int renderToOutput(BlockState state, long outputBuffer) {
        MemoryUtil.memSet(outputBuffer, 0, 16 * 16 * 8 * 6);

        boolean isBlock = true;
        if (state.getBlock() instanceof LiquidBlock) {
            isBlock = false;
        }

        RenderType blockRenderLayer = null;
        if (state.getBlock() instanceof LiquidBlock) {
            blockRenderLayer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
        } else {
            if (state.getBlock() instanceof LeavesBlock) {
                blockRenderLayer = RenderType.solid();
            } else {
                blockRenderLayer = ItemBlockRenderTypes.getChunkRenderType(state);
            }
        }

        // TODO: support block model entities
        // BakedBlockEntityModel bbem = null;
        if (state.hasBlockEntity()) {
            // bbem = BakedBlockEntityModel.bake(state);
        }

        boolean isAnyShaded = false;
        boolean isAnyDarkend = false;
        boolean anyTranslucent = false;
        boolean anyDiscard = false;
        if (isBlock) {
            this.opaqueVC.reset();
            this.translucentVC.reset();
            this.bakeBlockModel(state, blockRenderLayer);
            isAnyShaded |= this.opaqueVC.anyShaded | this.translucentVC.anyShaded;
            isAnyDarkend |= this.opaqueVC.anyDarkendTex | this.translucentVC.anyDarkendTex;
            anyTranslucent |= !this.translucentVC.isEmpty();
            anyDiscard |= this.opaqueVC.anyDiscard;
            if (!(this.opaqueVC.isEmpty() && this.translucentVC.isEmpty())) {// only render if there... is shit to
                                                                             // render
                for (int i = 0; i < VIEWS.length; i++) {
                    this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);
                    this.rasterizer.clear();
                    this.rasterizer.setBlending(false);
                    this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                    this.rasterizer.setBlending(true);
                    this.rasterizer.raster(VIEWS[i], this.translucentVC);
                    UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(),
                            outputBuffer + (SINGLE_FACE_OUTPUT_SIZE * i));
                }
            }
        } else {// Is fluid, slow path :(

            if (!(state.getBlock() instanceof LiquidBlock))
                throw new IllegalStateException();
            for (int i = 0; i < VIEWS.length; i++) {
                this.opaqueVC.reset();
                this.translucentVC.reset();
                this.bakeFluidState(state, i, blockRenderLayer);
                if (this.opaqueVC.isEmpty() && this.translucentVC.isEmpty())
                    continue;
                isAnyShaded |= this.opaqueVC.anyShaded | this.translucentVC.anyShaded;
                isAnyDarkend |= this.opaqueVC.anyDarkendTex | this.translucentVC.anyDarkendTex;
                anyTranslucent |= !this.translucentVC.isEmpty();
                anyDiscard |= this.opaqueVC.anyDiscard;

                this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);

                // The projection matrix
                this.rasterizer.clear();
                this.rasterizer.setBlending(false);
                this.rasterizer.raster(VIEWS[i], this.opaqueVC);
                this.rasterizer.setBlending(true);
                this.rasterizer.raster(VIEWS[i], this.translucentVC);
                UnsafeUtil.memcpy(this.rasterizer.getRawFramebuffer(), outputBuffer + (SINGLE_FACE_OUTPUT_SIZE * i));
            }
        }

        return (isAnyShaded ? 1 : 0) | (isAnyDarkend ? 2 : 0) | (anyTranslucent ? 4 : 0) | (anyDiscard ? 8 : 0);
    }

    static {
        // the face/direction is the face (e.g. down is the down face)
        addView(0, -90, 0, 0, 0);// Direction.DOWN
        addView(1, 90, 0, 0, 0b100);// Direction.UP

        addView(2, 0, 180, 0, 0b001);// Direction.NORTH
        addView(3, 0, 0, 0, 0);// Direction.SOUTH

        addView(4, 0, 90, 270, 0b100);// Direction.WEST
        addView(5, 0, 270, 270, 0);// Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f, 0.5f, 0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 0, 1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1, 0, 0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 1, 0), yaw));
        stack./*? if 1.20.1 { */mulPoseMatrix/*? } else { */mulPose/*? } */(
            new Matrix4f().scale(
                1 - 2 * (flip & 1),
                1 - (flip & 2),
                1 - ((flip >> 1) & 2)
            )
        );
        stack.translate(-0.5f, -0.5f, -0.5f);
        var mat = new Matrix4f(stack.last().pose());

        mat = new Matrix4f().set(
                2, 0, 0, 0,
                0, 2, 0, 0,
                0, 0, -2, 0,
                -1, -1, 1, 1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1 / Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
