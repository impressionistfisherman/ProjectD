package kr.dalmaru;
import kr.dalmaru.equipment.EnhancementRules;
public final class EnhancementRulesCheck { public static void main(String[] args) { if (EnhancementRules.chance(0, 0) != 100 || EnhancementRules.chance(4, 0) != 80 || EnhancementRules.chance(14, 0) != 100 || EnhancementRules.chance(14, 19) != 100) throw new IllegalStateException("Enhancement chance rule failed."); System.out.println("Enhancement rules verified."); } }
