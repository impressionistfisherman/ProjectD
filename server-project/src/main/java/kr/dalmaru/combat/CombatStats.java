package kr.dalmaru.combat;

/** Immutable server-side derived combat values. Equipment will be added as a separate source. */
public record CombatStats(int maximumHealth, int maximumStamina, double staminaRegenerationPerSecond, double strengthMultiplier) { }
