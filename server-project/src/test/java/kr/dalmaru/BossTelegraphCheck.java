package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.run.BossTelegraph;
import kr.dalmaru.run.BossTelegraph.*;

public final class BossTelegraphCheck {
    public static void main(String[] args) throws Exception {
        int minimum = ContentCatalog.current().mechanics().minimumTelegraphTicks();
        check(minimum == 20 && BossTelegraph.ticks(minimum) >= 20 && BossTelegraph.ticks(5) >= 30 && BossTelegraph.ticks(45) == 45, "Telegraph length floor");

        var circle = new Circle(3, 4, 5);
        check(circle.contains(8, 4) && circle.contains(3, 9) && circle.contains(6, 8) && circle.contains(3, 4), "Circle boundary is inclusive");
        check(!circle.contains(8.000001, 4) && !circle.contains(6.01, 8) && !circle.contains(3, 9.000001), "Circle outside");

        var sector = new Sector(0, 0, 2, 0, 7, Math.toRadians(60));
        check(sector.contains(7, 0) && sector.contains(0, 0) && sector.contains(3.5, 3.5 * Math.sqrt(3)) && sector.contains(3.5, -3.5 * Math.sqrt(3)), "Sector boundary is inclusive");
        check(!sector.contains(7.0001, 0) && !sector.contains(3.5, 6.1) && !sector.contains(-1, 0) && !sector.contains(0, 3), "Sector outside");

        var band = new Band(0, 0, 1, 0, 24, 2);
        check(band.contains(0, 1) && band.contains(24, -1) && band.contains(12, 0) && band.contains(0, 0), "Band boundary is inclusive");
        check(!band.contains(24.0001, 0) && !band.contains(-0.0001, 0) && !band.contains(10, 1.0001) && !band.contains(10, -1.0001), "Band outside");
        var rotated = new Band(5, 5, 0, 3, 24, 2);
        check(rotated.contains(6, 29) && rotated.contains(4, 5) && !rotated.contains(6.001, 10), "Rotated band");

        // Displayed points are exactly the hit shape: boundary and fill points hit, points just beyond the boundary do not.
        for (int degrees = 0; degrees < 360; degrees += 17) {
            double dx = Math.cos(Math.toRadians(degrees)), dz = Math.sin(Math.toRadians(degrees));
            for (Shape shape : List.<Shape>of(new Circle(dx * 3, dz * 2, 5), new Sector(1, -2, dx, dz, 7, Math.toRadians(60)), new Band(-3, 4, dx, dz, 24, 2))) {
                for (double spacing : new double[]{1.0, 1.5, 0.7}) {
                    check(!shape.outline(spacing).isEmpty() && !shape.fill(spacing).isEmpty(), "Empty display");
                    for (var point : shape.outline(spacing)) check(shape.contains(point[0], point[1]), "Displayed boundary point is not a hit " + shape);
                    for (var point : shape.fill(spacing)) check(shape.contains(point[0], point[1]), "Displayed fill point is not a hit " + shape);
                }
            }
            var c = new Circle(1, 2, 5);
            for (var point : c.outline(1.0)) check(!c.contains(1 + (point[0] - 1) * 1.001, 2 + (point[1] - 2) * 1.001), "Circle outline is not the boundary");
            var s = new Sector(1, -2, dx, dz, 7, Math.toRadians(60));
            for (var point : s.outline(1.0)) {
                double vx = point[0] - 1, vz = point[1] + 2;
                if (Math.hypot(vx, vz) > 6.99) check(!s.contains(1 + vx * 1.001, -2 + vz * 1.001), "Sector arc is not the boundary");
            }
        }

        // 19 / 20 tick boundary, one hit, same-tick replay and paused time.
        var shape = BossTelegraph.shape(Kind.FLAME, 0, 0, 10, 0);
        boolean rejected = false;
        try { new Active(Kind.FLAME, shape, 180, true, 19, 20, 100); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "A telegraph below the 20 tick minimum was accepted");
        var active = new Active(Kind.FLAME, shape, 180, true, 20, 20, 100);
        check(!active.tick(100) && active.elapsed() == 0, "Start tick must not count");
        for (long tick = 101; tick <= 119; tick++) check(!active.tick(tick) && !active.tick(tick), "Damage before 20 ticks at " + tick);
        check(active.elapsed() == 19 && !active.done(), "19 ticks");
        check(active.tick(120) && active.done(), "20th tick must fire");
        check(!active.tick(120) && !active.tick(121) && !active.tick(5000), "Fired twice");
        var paused = new Active(Kind.FLAME, shape, 180, true, 30, 20, 0);
        for (long tick = 1; tick <= 5; tick++) paused.tick(tick);
        check(!paused.tick(90000) && paused.elapsed() == 6, "Paused time must not release a burst");
        check(!paused.tick(10) && paused.elapsed() == 6, "Old tick");

        // Shape is fixed at start; the hit uses positions at the firing tick (dodge).
        var flame = BossTelegraph.shape(Kind.FLAME, 0, 0, 10, 3);
        check(flame instanceof Circle c1 && c1.cx() == 10 && c1.cz() == 3 && c1.radius() == 5, "Flame centred on the target");
        check(flame.contains(10, 3) && !flame.contains(16, 3), "Dodged flame");
        var spear = BossTelegraph.shape(Kind.SPEAR, 0, 0, 0, 10);
        check(spear.contains(0, 10) && spear.contains(0, 24) && !spear.contains(0, 24.01) && !spear.contains(1.01, 10), "Spear runs from the boss through the target");
        var wave = BossTelegraph.shape(Kind.WAVE, 1, 1, 1, 6);
        check(wave.contains(1, 6) && wave.contains(1, 8) && !wave.contains(1, 8.01) && !wave.contains(1, 0), "Wave is a forward sector");
        check(BossTelegraph.shape(Kind.SPEAR, 2, 2, 2, 2) instanceof Band, "Target on the boss must still give a shape");

        // Phase patterns and numbers (unchanged boss damage numbers).
        check(BossTelegraph.available(1).equals(List.of(Kind.SPEAR)) && BossTelegraph.available(.71).equals(List.of(Kind.SPEAR))
                && BossTelegraph.available(.7).equals(List.of(Kind.SPEAR, Kind.WAVE)) && BossTelegraph.available(.36).equals(List.of(Kind.SPEAR, Kind.WAVE))
                && BossTelegraph.available(.35).equals(List.of(Kind.SPEAR, Kind.WAVE, Kind.FLAME)), "Phase boundaries");
        check(BossTelegraph.pick(.2, 0) == Kind.SPEAR && BossTelegraph.pick(.2, 1) == Kind.WAVE && BossTelegraph.pick(.2, 2) == Kind.FLAME && BossTelegraph.pick(.2, 3) == Kind.SPEAR && BossTelegraph.pick(.9, 5) == Kind.SPEAR, "Pattern rotation");
        check(BossTelegraph.baseDamage(Kind.SPEAR) == 130 && BossTelegraph.baseDamage(Kind.WAVE) == 120 && BossTelegraph.baseDamage(Kind.FLAME) == 180
                && !BossTelegraph.magic(Kind.SPEAR) && BossTelegraph.magic(Kind.WAVE) && BossTelegraph.magic(Kind.FLAME), "Damage numbers");

        // Vanilla fire/smoke only.
        check(List.of(Cue.values()).stream().map(Enum::name).collect(Collectors.toSet()).equals(java.util.Set.of("FLAME", "SMOKE", "SOUL_FIRE_FLAME")), "Allowed cue registry");
        var runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        int from = runtime.indexOf("    private void startTelegraph("), to = runtime.indexOf("    private void announceCollapse(");
        String section = runtime.substring(from, to);
        var particles = Pattern.compile("ParticleTypes\\.(\\w+)").matcher(section).results().map(m -> m.group(1)).collect(Collectors.toSet());
        var sounds = Pattern.compile("SoundEvents\\.(\\w+)").matcher(section).results().map(m -> m.group(1)).collect(Collectors.toSet());
        check(particles.equals(java.util.Set.of("FLAME", "SMOKE", "SOUL_FIRE_FLAME")), "Telegraph particles must be vanilla fire/smoke only: " + particles);
        check(sounds.equals(java.util.Set.of("EVOKER_PREPARE_ATTACK", "BLAZE_SHOOT")), "Telegraph sounds: " + sounds);
        check(section.contains("shape.contains(player.getX(),player.getZ())") && section.contains("shape.contains(body.getX(),body.getZ())")
                && !section.contains("distanceToSqr") && !section.contains("damage(players"), "Hit must use the displayed shape only");
        check(section.contains("encounter.telegraphs.clear();return;") && runtime.contains("encounter.telegraphs.clear();encounter.bossCycles=0;")
                && runtime.contains("tickTelegraphs(server,encounter);"), "Cleanup and tick wiring");
        int beacon = runtime.indexOf("} else if (encounter.stage == Stage.BEACON) {");
        String beaconCode = runtime.substring(beacon, runtime.indexOf("    private List<BossView> bossViews("));
        check(beaconCode.indexOf("startTelegraph(") > 0 && beaconCode.indexOf("startTelegraph(") < beaconCode.indexOf("if (ratio > .7F) {"), "Telegraph path must come before the legacy instant attacks");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
