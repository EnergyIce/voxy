package me.cortex.voxy.common.voxelization;

import me.cortex.voxy.common.Logger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

//Detects blocks whose real appearance is stored in a BlockEntity instead of their BlockState
// (Framed Blocks' camo, Create's Copycat "material", and the Create-addon "Copycats+"'s own
// separate copycat implementation). Voxy bakes exactly one texture per distinct BlockState (see
// Mapper), so these blocks would otherwise always bake using their bare/no-camo appearance -
// which for these mods is usually empty geometry.
//
// This class only answers "does this block need per-instance handling" - the actual per-instance
// baking is generic and mod-agnostic (see InstanceKeyRegistry / InstanceModelBaker): it captures
// the real BlockEntity via the plain vanilla BlockEntity#saveWithoutMetadata() API and renders it
// through its real BlockEntityRenderer, so it doesn't need any mod-specific "give me the material"
// reflection at all - and it automatically keeps the block's real shape (panels/steps included),
// since it's rendering the actual block, not swapping BlockStates.
//
// Detection itself still needs to know which mods to opt in to this (comparatively expensive)
// path, resolved via reflection so this compiles and works fine without any of these mods installed.
public class CamouflageBlockCompat {
    private static volatile boolean initialized = false;

    private static Class<?> framedBlockEntityClass;
    private static Class<?> copycatBlockClass;
    private static Class<?> copycatsPlusBlockClass;

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;

        try {
            framedBlockEntityClass = Class.forName("xfacthd.framedblocks.api.block.FramedBlockEntity");
            Logger.info("Voxy: Framed Blocks instance-render compat enabled");
        } catch (ReflectiveOperationException e) {
            framedBlockEntityClass = null;
            Logger.warn("Voxy: Framed Blocks instance-render compat unavailable (" + e + ")");
        }

        try {
            copycatBlockClass = Class.forName("com.simibubi.create.content.decoration.copycat.CopycatBlock");
            Logger.info("Voxy: Create Copycat instance-render compat enabled");
        } catch (ReflectiveOperationException e) {
            copycatBlockClass = null;
            Logger.warn("Voxy: Create Copycat instance-render compat unavailable (" + e + ")");
        }

        //"Copycats+" (mod id "copycats") is a separate Create addon that adds more copycat variants
        // (extra panel/step shapes, sliding doors, cogwheels, pipes, ...). Its blocks all implement
        // com.copycatsplus.copycats.foundation.copycat.ICopycatBlock.
        try {
            copycatsPlusBlockClass = Class.forName("com.copycatsplus.copycats.foundation.copycat.ICopycatBlock");
            Logger.info("Voxy: Copycats+ instance-render compat enabled");
        } catch (ReflectiveOperationException e) {
            copycatsPlusBlockClass = null;
            Logger.warn("Voxy: Copycats+ instance-render compat unavailable (" + e + ")");
        }
    }

    //Cheap pre-check usable on a bare BlockState (e.g. while scanning a section's palette),
    // before any BlockEntity is available.
    public static boolean mightNeedResolve(Block block) {
        init();
        if (copycatBlockClass != null && copycatBlockClass.isInstance(block)) {
            return true;
        }
        if (copycatsPlusBlockClass != null && copycatsPlusBlockClass.isInstance(block)) {
            return true;
        }
        if (framedBlockEntityClass != null) {
            var key = BuiltInRegistries.BLOCK.getKey(block);
            return key != null && "framedblocks".equals(key.getNamespace());
        }
        return false;
    }
}
