package com.meshi.forest;

// one part of floor 2 (castle, tree, bridge...) as a small block grid. pure java, no minecraft classes.
// coords you pass in are relative to the anchor: x / z from the middle block, y 0 = base of the part.
public final class PartGrid {
    public enum Mat {
        AIR, TRUNK, LOG_X, LOG_Z, BEAM, STONE, MSTONE, BAND, PLANK, RAIL, MOSS, LEAF, LEAF2, LEAF3, VINE, NEST, EGG,
        LANTERN, LADDER_N, LADDER_S, LADDER_E, LADDER_W
    }

    private static final Mat[] MATS = Mat.values();

    public final int sx, sy, sz, ax, ay, az;
    private final byte[] cells;
    // writes that fell outside the grid (should stay 0, the test checks it)
    public int outside = 0;

    public PartGrid(ForestDefs.Def d) {
        this.sx = d.sx;
        this.sy = d.sy;
        this.sz = d.sz;
        this.ax = d.ax;
        this.ay = d.ay;
        this.az = d.az;
        this.cells = new byte[this.sx * this.sy * this.sz];
    }

    private int idx(int x, int y, int z) {
        return (x * this.sy + y) * this.sz + z;
    }

    private boolean in(int x, int y, int z) {
        return x >= 0 && x < this.sx && y >= 0 && y < this.sy && z >= 0 && z < this.sz;
    }

    // ---- grid coords (0 .. size-1), used by the builder
    public Mat at(int x, int y, int z) {
        if (!in(x, y, z)) {
            return null;
        }
        byte v = this.cells[idx(x, y, z)];
        return v == 0 ? null : MATS[v - 1];
    }

    public void setAt(int x, int y, int z, Mat m) {
        if (in(x, y, z)) {
            this.cells[idx(x, y, z)] = m == null ? 0 : (byte) (m.ordinal() + 1);
        }
    }

    // ---- anchor coords, used by the shape code
    public Mat get(int rx, int ry, int rz) {
        return at(rx + this.ax, ry + this.ay, rz + this.az);
    }

    public void put(int rx, int ry, int rz, Mat m) {
        int x = rx + this.ax, y = ry + this.ay, z = rz + this.az;
        if (!in(x, y, z)) {
            this.outside++;
            return;
        }
        this.cells[idx(x, y, z)] = (byte) (m.ordinal() + 1);
    }

    // only if nothing is there yet
    public void putAir(int rx, int ry, int rz, Mat m) {
        if (get(rx, ry, rz) == null) {
            put(rx, ry, rz, m);
        }
    }

    // turns an existing block into air (a doorway / window / hole)
    public void carve(int rx, int ry, int rz) {
        if (get(rx, ry, rz) != null) {
            put(rx, ry, rz, Mat.AIR);
        }
    }

    public int count() {
        int n = 0;
        for (byte b : this.cells) {
            if (b != 0 && b != 1) {
                n++;
            }
        }
        return n;
    }
}
