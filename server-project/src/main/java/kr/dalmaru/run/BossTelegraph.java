package kr.dalmaru.run;

import java.util.ArrayList;
import java.util.List;

/**
 * Boss attack telegraphs (P4-S05). One server shape is used both for the particles that are shown and for the hit
 * test, on the character's feet position projected to XZ. Boundaries are inclusive. Time advances only through
 * {@link Active#tick(long)}, so a paused encounter never releases a late burst of damage.
 */
public final class BossTelegraph {
    public static final double EPSILON = 1e-9;
    public static final int DEFAULT_TICKS = 30;

    public enum Kind { SPEAR, WAVE, FLAME }
    /** Vanilla particles allowed for telegraphs: fire and smoke only. */
    public enum Cue { FLAME, SMOKE, SOUL_FIRE_FLAME }

    private BossTelegraph() {}

    public sealed interface Shape permits Circle, Sector, Band {
        boolean contains(double x, double z);
        /** Points on the boundary (corners and arc ends included). */
        List<double[]> outline(double spacing);
        /** Axis aligned bounds: minX, minZ, maxX, maxZ. */
        double[] bounds();
        /** Grid points inside the shape; every point satisfies {@link #contains}. */
        default List<double[]> fill(double spacing) {
            var b = bounds();
            var result = new ArrayList<double[]>();
            for (double x = b[0]; x <= b[2] + EPSILON; x += spacing)
                for (double z = b[1]; z <= b[3] + EPSILON; z += spacing) if (contains(x, z)) result.add(new double[]{x, z});
            return result;
        }
    }

    public record Circle(double cx, double cz, double radius) implements Shape {
        public Circle { if (!(radius > 0) || !Double.isFinite(radius + cx + cz)) throw new IllegalArgumentException("Invalid circle."); }
        @Override public boolean contains(double x, double z) { double dx = x - cx, dz = z - cz; return dx * dx + dz * dz <= radius * radius + EPSILON; }
        @Override public List<double[]> outline(double spacing) {
            int count = Math.max(8, (int) Math.ceil(2 * Math.PI * radius / spacing));
            var result = new ArrayList<double[]>();
            for (int i = 0; i < count; i++) { double angle = 2 * Math.PI * i / count; result.add(new double[]{cx + radius * Math.cos(angle), cz + radius * Math.sin(angle)}); }
            return result;
        }
        @Override public double[] bounds() { return new double[]{cx - radius, cz - radius, cx + radius, cz + radius}; }
    }

    /** Circular sector from an origin along a direction; the half angle is in radians. */
    public record Sector(double ox, double oz, double dx, double dz, double radius, double halfAngle) implements Shape {
        public Sector {
            double length = Math.hypot(dx, dz);
            if (!(length > 0) || !(radius > 0) || !(halfAngle > 0) || halfAngle >= Math.PI || !Double.isFinite(ox + oz + length + radius)) throw new IllegalArgumentException("Invalid sector.");
            dx /= length; dz /= length;
        }
        @Override public boolean contains(double x, double z) {
            double vx = x - ox, vz = z - oz, distanceSq = vx * vx + vz * vz;
            if (distanceSq <= EPSILON) return true;
            if (distanceSq > radius * radius + EPSILON) return false;
            return vx * dx + vz * dz >= Math.cos(halfAngle) * Math.sqrt(distanceSq) - EPSILON;
        }
        private double[] edge(double sign) {
            double cos = Math.cos(halfAngle), sin = sign * Math.sin(halfAngle);
            return new double[]{dx * cos - dz * sin, dx * sin + dz * cos};
        }
        @Override public List<double[]> outline(double spacing) {
            var result = new ArrayList<double[]>();
            for (double sign : new double[]{-1, 1}) {
                var edge = edge(sign);
                int count = Math.max(1, (int) Math.ceil(radius / spacing));
                for (int i = 0; i <= count; i++) result.add(new double[]{ox + edge[0] * radius * i / count, oz + edge[1] * radius * i / count});
            }
            int arc = Math.max(2, (int) Math.ceil(2 * halfAngle * radius / spacing));
            double base = Math.atan2(dz, dx);
            for (int i = 0; i <= arc; i++) {
                double angle = base - halfAngle + 2 * halfAngle * i / arc;
                result.add(new double[]{ox + radius * Math.cos(angle), oz + radius * Math.sin(angle)});
            }
            return result;
        }
        @Override public double[] bounds() { return new double[]{ox - radius, oz - radius, ox + radius, oz + radius}; }
    }

    /** Straight band from an origin along a direction, {@code width} wide in total. */
    public record Band(double ox, double oz, double dx, double dz, double length, double width) implements Shape {
        public Band {
            double norm = Math.hypot(dx, dz);
            if (!(norm > 0) || !(length > 0) || !(width > 0) || !Double.isFinite(ox + oz + norm + length + width)) throw new IllegalArgumentException("Invalid band.");
            dx /= norm; dz /= norm;
        }
        @Override public boolean contains(double x, double z) {
            double vx = x - ox, vz = z - oz, along = vx * dx + vz * dz, side = Math.abs(vx * dz - vz * dx);
            return along >= -EPSILON && along <= length + EPSILON && side <= width / 2 + EPSILON;
        }
        @Override public List<double[]> outline(double spacing) {
            double px = -dz * width / 2, pz = dx * width / 2;
            var result = new ArrayList<double[]>();
            double[][] corners = {{ox - px, oz - pz}, {ox + px, oz + pz}, {ox + px + dx * length, oz + pz + dz * length}, {ox - px + dx * length, oz - pz + dz * length}};
            for (int i = 0; i < 4; i++) {
                var from = corners[i]; var to = corners[(i + 1) % 4];
                int count = Math.max(1, (int) Math.ceil(Math.hypot(to[0] - from[0], to[1] - from[1]) / spacing));
                for (int step = 0; step < count; step++) result.add(new double[]{from[0] + (to[0] - from[0]) * step / count, from[1] + (to[1] - from[1]) * step / count});
            }
            return result;
        }
        @Override public double[] bounds() {
            double px = -dz * width / 2, pz = dx * width / 2;
            double[] xs = {ox - px, ox + px, ox + px + dx * length, ox - px + dx * length}, zs = {oz - pz, oz + pz, oz + pz + dz * length, oz - pz + dz * length};
            double minX = xs[0], maxX = xs[0], minZ = zs[0], maxZ = zs[0];
            for (int i = 1; i < 4; i++) { minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); minZ = Math.min(minZ, zs[i]); maxZ = Math.max(maxZ, zs[i]); }
            return new double[]{minX, minZ, maxX, maxZ};
        }
    }

    /** Patterns usable at the boss health ratio: 100-70% spear, 70-35% spear+wave, below that all three. */
    public static List<Kind> available(double ratio) {
        return ratio > .7 ? List.of(Kind.SPEAR) : ratio > .35 ? List.of(Kind.SPEAR, Kind.WAVE) : List.of(Kind.SPEAR, Kind.WAVE, Kind.FLAME);
    }
    public static Kind pick(double ratio, int cycle) { var kinds = available(ratio); return kinds.get(Math.floorMod(cycle, kinds.size())); }
    public static int baseDamage(Kind kind) { return switch (kind) { case SPEAR -> 130; case WAVE -> 120; case FLAME -> 180; }; }
    public static boolean magic(Kind kind) { return kind != Kind.SPEAR; }
    public static Cue cue(Kind kind) { return switch (kind) { case SPEAR -> Cue.FLAME; case WAVE -> Cue.SMOKE; case FLAME -> Cue.SOUL_FIRE_FLAME; }; }
    /** Telegraph length: at least the content minimum (20 server ticks). */
    public static int ticks(int contentMinimum) { return Math.max(DEFAULT_TICKS, contentMinimum); }

    /** Shape for a pattern, fixed when the telegraph starts. The spear and wave start at the boss, the flame at the target. */
    public static Shape shape(Kind kind, double bossX, double bossZ, double targetX, double targetZ) {
        double dx = targetX - bossX, dz = targetZ - bossZ;
        if (Math.hypot(dx, dz) < 1e-6) { dx = 0; dz = 1; }
        return switch (kind) {
            case SPEAR -> new Band(bossX, bossZ, dx, dz, 24, 2);
            case WAVE -> new Sector(bossX, bossZ, dx, dz, 7, Math.toRadians(60));
            case FLAME -> new Circle(targetX, targetZ, 5);
        };
    }

    /** One running telegraph: shown for {@code ticks} live ticks, then it hits once with the same shape. */
    public static final class Active {
        private final Kind kind;
        private final Shape shape;
        private final float damage;
        private final boolean magic;
        private final int total;
        private final long startTick;
        private long lastTick;
        private int elapsed;
        private boolean fired;

        public Active(Kind kind, Shape shape, float damage, boolean magic, int ticks, int contentMinimum, long startTick) {
            if (contentMinimum < 1 || ticks < contentMinimum || !(damage > 0)) throw new IllegalArgumentException("Invalid telegraph.");
            this.kind = kind; this.shape = shape; this.damage = damage; this.magic = magic; this.total = ticks;
            this.startTick = startTick; this.lastTick = startTick;
        }

        /** Counts one live tick after the start tick; returns true only on the tick the attack fires. */
        public boolean tick(long serverTick) {
            if (fired || serverTick <= lastTick) return false;
            lastTick = serverTick;
            if (++elapsed < total) return false;
            fired = true;
            return true;
        }
        public Kind kind() { return kind; }
        public Shape shape() { return shape; }
        public float damage() { return damage; }
        public boolean magic() { return magic; }
        public int elapsed() { return elapsed; }
        public int total() { return total; }
        public boolean done() { return fired; }
        public long startTick() { return startTick; }
    }
}
