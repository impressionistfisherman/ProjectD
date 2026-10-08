package kr.dalmaru.run;

import java.util.UUID;

public record RunReward(UUID characterId, int copper, int ironstone, int beaconToken, String equipmentBox) { }
