package me.cortex.voxy.common.voxelization;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

//Per-world registry assigning a stable, session-scoped integer index to each distinct
//(wrapper BlockState, BlockEntity NBT) pair encountered for a camouflage/mimicry block
//(Framed Blocks, Create Copycat, ...). Used to point a voxel at a per-instance baked model
//instead of the shared per-BlockState one (see Mapper#withInstanceOverride and
//me.cortex.voxy.client.core.model.InstanceModelBaker, which does the actual GL baking).
//
//Entries are persisted through the world's Mapper (see Mapper#persistInstanceEntry) because stored voxel
//data references them by index and must keep resolving after a restart. An index that still doesn't
//resolve (lost/corrupt entry) simply falls back to the wrapper block's plain per-BlockState appearance -
//safe, graceful degradation, never a crash or garbage render.
public class InstanceKeyRegistry {
    public record Key(BlockState wrapperState, CompoundTag nbt) {}

    //26 bits reserved in the packed voxel id (see Mapper), so the index must fit
    public static final int MAX_INSTANCES = 1<<26;
    //Safety net: a single block type creating this many distinct instances in one session is not a set of real
    // appearances but volatile block entity data that slipped through canonicalizeNbt() - stop registering new
    // instances for it (they keep their plain appearance) instead of growing memory and storage without bound.
    private static final int MAX_NEW_INSTANCES_PER_BLOCK = 4096;
    private final java.util.HashMap<Block, Integer> newPerBlock = new java.util.HashMap<>();

    private final ReentrantLock lock = new ReentrantLock();
    private final ConcurrentHashMap<Key, Integer> key2index = new ConcurrentHashMap<>(256, 0.75f, 8);
    private final java.util.ArrayList<Key> index2key = new java.util.ArrayList<>();
    private final Mapper mapper;

    //Stored index -> the index that actually represents its (canonicalized) key, for stored indices that are
    // duplicates of another one, or -1 for stored instances whose data doesn't need an instance any more. Stored
    // voxel data still references every index it was ingested with: older versions registered one index per
    // variation of volatile block entity data (CRN displays, traffic lights, ... - tens of thousands in a busy
    // world), which now collapse to a handful of keys. Without this every one of those indices was baked on its
    // own each session, which could use up the whole model id space and keep the LoDs waiting for minutes.
    // Only written in the constructor, read-only afterwards (safe to read from any thread).
    private final Int2IntOpenHashMap aliases = new Int2IntOpenHashMap();

    public InstanceKeyRegistry(Mapper mapper) {
        this.mapper = mapper;
        this.aliases.defaultReturnValue(Integer.MIN_VALUE);
        for (var entry : mapper.loadInstanceEntries()) {
            while (this.index2key.size() <= entry.index()) {
                this.index2key.add(null);//Gap (entry lost/unreadable): index stays unresolved, never reused
            }
            //Canonicalized on load too, so entries stored by older versions with volatile data collapse again
            var key = new Key(entry.state(), canonicalizeNbt(entry.nbt()));
            this.index2key.set(entry.index(), key);
            if (!needsInstance(entry.nbt())) {
                this.aliases.put(entry.index(), -1);
                continue;
            }
            Integer existing = this.key2index.putIfAbsent(key, entry.index());
            if (existing != null && existing != entry.index()) {
                this.aliases.put(entry.index(), (int) existing);
            }
        }
        if (!this.aliases.isEmpty()) {
            Logger.info("Voxy: " + this.aliases.size() + " stored camouflage instances are duplicates or no longer needed, sharing their models");
        }
    }

    //The index whose baked model a stored instance index uses (itself unless it's a duplicate), or -1 if the voxel
    // should just use its block's normal model
    public int canonicalIndex(int index) {
        int alias = this.aliases.get(index);
        return alias == Integer.MIN_VALUE ? index : alias;
    }

    //Returns the stable index for this key, registering it if it's new. Thread-safe, callable
    //from ingest worker threads.
    //Returns -1 when the block entity data doesn't change the block's appearance, so the normal per-BlockState
    // model is correct and no instance is needed.
    public int getOrCreateIndex(BlockState wrapperState, CompoundTag rawNbt) {
        if (!needsInstance(rawNbt)) {
            return -1;
        }
        var nbt = canonicalizeNbt(rawNbt);
        var key = new Key(wrapperState, nbt);
        var existing = this.key2index.get(key);
        if (existing != null) {
            return existing;
        }
        this.lock.lock();
        try {
            existing = this.key2index.get(key);
            if (existing != null) {
                return existing;
            }
            int index = this.index2key.size();
            if (index >= MAX_INSTANCES) {
                throw new IllegalStateException("Exceeded max camouflage instance count: " + MAX_INSTANCES);
            }
            int perBlock = this.newPerBlock.merge(wrapperState.getBlock(), 1, Integer::sum);
            if (perBlock > MAX_NEW_INSTANCES_PER_BLOCK) {
                if (perBlock == MAX_NEW_INSTANCES_PER_BLOCK + 1) {
                    Logger.warn("Voxy: " + wrapperState.getBlock() + " produced over " + MAX_NEW_INSTANCES_PER_BLOCK + " distinct camouflage instances this session (volatile block entity data?), further ones keep their plain appearance");
                }
                throw new IllegalStateException("Too many camouflage instances for " + wrapperState.getBlock());
            }
            this.index2key.add(key);
            this.key2index.put(key, index);
            this.mapper.persistInstanceEntry(index, wrapperState, nbt);
            return index;
        } finally {
            this.lock.unlock();
        }
    }

    //Removes block entity data that changes over time without changing the block's baked appearance. Every change
    // of the stored data would otherwise register (and persist, and bake) a brand new instance on each re-ingest.
    //  - ForgeCaps: capability data attached by other mods, never part of a block model
    //  - Create Railways Navigator displays (Create copycat based): live train data and refresh timestamp; the
    //    displayed text is drawn by a block entity renderer, not by the model
    //  - TrafficCraft: only the paint colour affects the block model. Everything else is renderer-only or live
    //    state (traffic light phase/timers/lit lamps/links with coordinates, sign text) and is dropped.
    //TrafficCraft blocks that were never painted (color -1 = PaintColor.NONE) look exactly like their normal model,
    // which bakes their default colour (see ModelFactory#captureColourConstant) - no instance needed for them.
    public static boolean needsInstance(CompoundTag nbt) {
        if (nbt != null && nbt.getString("id").startsWith("trafficcraft:")) {
            return nbt.contains("color") && nbt.getInt("color") != -1;
        }
        return true;
    }

    public static CompoundTag canonicalizeNbt(CompoundTag nbt) {
        if (nbt == null) return null;
        if (nbt.getString("id").startsWith("trafficcraft:")) {
            CompoundTag tc = new CompoundTag();
            tc.putString("id", nbt.getString("id"));
            if (nbt.contains("color")) tc.put("color", nbt.get("color").copy());
            return tc;
        }
        CompoundTag out = nbt.copy();
        out.remove("ForgeCaps");
        if (out.getString("id").startsWith("createrailwaysnavigator:")) {
            out.remove("LastRefreshed");
            out.remove("TrainStops");
        }
        if (out.getString("id").startsWith("pantographsandwires:")) {
            //Wire network node id: unique per connector and irrelevant for the model (the cantilever shape only)
            out.remove("NodeId");
        }
        return out;
    }

    //Nullable: an index from a stale/previous-session voxel may not be registered yet this session
    public Key getKey(int index) {
        this.lock.lock();
        try {
            if (index < 0 || index >= this.index2key.size()) {
                return null;
            }
            return this.index2key.get(index);
        } finally {
            this.lock.unlock();
        }
    }
}
