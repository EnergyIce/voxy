package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import net.minecraft.util.Mth;
import org.joml.*;

import java.lang.reflect.Field;

public abstract class Viewport <A extends Viewport<A>> {
    //public final HiZBuffer2 hiZBuffer = new HiZBuffer2();
    public final HiZBuffer hiZBuffer;
    //Which sections vanilla draws (see ChunkBoundRenderer): per viewport header (camera, sodium distance, grid layout)
    // and the section bitmask shared by all viewports (owned by the ChunkBoundRenderer)
    public final GlBuffer vanillaMaskHeader = new GlBuffer(64);
    public GlBuffer vanillaSectionMask;

    private static final Field planesField;
    static {
        try {
            planesField = FrustumIntersection.class.getDeclaredField("planes");
            planesField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public int width;
    public int height;
    public int frameId;
    public Matrix4f vanillaProjection = new Matrix4f();
    public Matrix4f projection = new Matrix4f();
    public Matrix4f modelView = new Matrix4f();
    public final FrustumIntersection frustum = new FrustumIntersection();
    public final Vector4f[] frustumPlanes;
    public double cameraX;
    public double cameraY;
    public double cameraZ;

    public final Matrix4f MVP = new Matrix4f();
    public final Vector3i section = new Vector3i();
    public final Vector3f innerTranslation = new Vector3f();

    private final RenderProperties properties;

    protected Viewport(RenderProperties properties) {
        Vector4f[] planes = null;
        try {
             planes = (Vector4f[]) planesField.get(this.frustum);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        this.frustumPlanes = planes;

        this.properties = properties;
        this.hiZBuffer = new HiZBuffer(properties);
    }

    public final void delete() {
        this.delete0();
    }

    protected void delete0() {
        this.hiZBuffer.free();
        this.vanillaMaskHeader.free();
    }

    public A setVanillaProjection(Matrix4fc projection) {
        this.vanillaProjection.set(projection);
        return (A) this;
    }

    public A setProjection(Matrix4f projection) {
        this.projection = projection;
        return (A) this;
    }

    public A setModelView(Matrix4fc modelView) {
        this.modelView.set(modelView);
        return (A) this;
    }

    public A setCamera(double x, double y, double z) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
        return (A) this;
    }

    public A setScreenSize(int width, int height) {
        this.width = width;
        this.height = height;
        return (A) this;
    }

    public A update() {
        //MVP
        this.projection.mul(this.modelView, this.MVP);

        //Update the frustum
        this.frustum.set(this.MVP, false);

        //Translation vectors
        int sx = Mth.floor(this.cameraX)>>5;
        int sy = Mth.floor(this.cameraY)>>5;
        int sz = Mth.floor(this.cameraZ)>>5;
        this.section.set(sx, sy, sz);

        this.innerTranslation.set(
                (float) (this.cameraX-(sx<<5)),
                (float) (this.cameraY-(sy<<5)),
                (float) (this.cameraZ-(sz<<5)));

        return (A) this;
    }

    public abstract GlBuffer getRenderList();
}
