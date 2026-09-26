package me.cortex.voxy.common.voxelization;

import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

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

    private final ReentrantLock lock = new ReentrantLock();
    private final ConcurrentHashMap<Key, Integer> key2index = new ConcurrentHashMap<>(256, 0.75f, 8);
    private final java.util.ArrayList<Key> index2key = new java.util.ArrayList<>();
    private final Mapper mapper;

    public InstanceKeyRegistry(Mapper mapper) {
        this.mapper = mapper;
        for (var entry : mapper.loadInstanceEntries()) {
            while (this.index2key.size() <= entry.index()) {
                this.index2key.add(null);//Gap (entry lost/unreadable): index stays unresolved, never reused
            }
            var key = new Key(entry.state(), entry.nbt());
            this.index2key.set(entry.index(), key);
            this.key2index.putIfAbsent(key, entry.index());
        }
    }

    //Returns the stable index for this key, registering it if it's new. Thread-safe, callable
    //from ingest worker threads.
    public int getOrCreateIndex(BlockState wrapperState, CompoundTag nbt) {
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
            this.index2key.add(key);
            this.key2index.put(key, index);
            this.mapper.persistInstanceEntry(index, wrapperState, nbt);
            return index;
        } finally {
            this.lock.unlock();
        }
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
