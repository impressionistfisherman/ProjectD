package kr.dalmaru.content;

import java.util.List;

/** Frozen P4 values; legacy snapshots have no P4 mechanics. */
public record BeaconMechanics(Brazier brazier, Boss boss, int minimumTelegraphTicks) {
    public BeaconMechanics {
        java.util.Objects.requireNonNull(brazier);
        java.util.Objects.requireNonNull(boss);
        if (minimumTelegraphTicks < 20) throw new IllegalArgumentException("Telegraph must last at least one second");
    }
    public record Brazier(int channelTicks, double cancelDistance, double interactionRadius,
                          int overlapTicks, List<Integer> counts, List<Integer> simultaneous,
                          int idleTicks, int warningTicks, int pulseTicks, double maximumHealthFraction) {
        public Brazier {
            counts = List.copyOf(counts); simultaneous = List.copyOf(simultaneous);
            if (channelTicks < 1 || !positive(cancelDistance) || !positive(interactionRadius)
                    || cancelDistance > interactionRadius || overlapTicks < 1 || overlapTicks > channelTicks
                    || counts.size() != 3 || simultaneous.size() != 3 || idleTicks < 1 || warningTicks < 1
                    || pulseTicks < 1 || !positive(maximumHealthFraction) || maximumHealthFraction > 1)
                throw new IllegalArgumentException("Invalid brazier values");
            for (int i = 0; i < 3; i++) if (counts.get(i) < 1 || simultaneous.get(i) < 1 || simultaneous.get(i) > counts.get(i))
                throw new IllegalArgumentException("Invalid brazier tier");
        }
    }
    public record Boss(int gatekeeperPosture, int beaconPosture, int wallPostureDamage,
                       List<Double> postureScales, double trainingPostureScale,
                       int collapseTicks, double collapsedDamageScale, int immunityTicks) {
        public Boss {
            postureScales = List.copyOf(postureScales);
            if (gatekeeperPosture < 1 || beaconPosture < 1 || wallPostureDamage < 1 || postureScales.size() != 3
                    || postureScales.stream().anyMatch(value -> !positive(value) || value > 100)
                    || !positive(trainingPostureScale) || collapseTicks < 1
                    || !positive(collapsedDamageScale) || collapsedDamageScale < 1 || immunityTicks < 1)
                throw new IllegalArgumentException("Invalid boss values");
        }
    }
    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
}
