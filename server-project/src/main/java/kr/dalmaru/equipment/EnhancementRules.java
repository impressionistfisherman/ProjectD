package kr.dalmaru.equipment;

public final class EnhancementRules {
	private EnhancementRules() { }
	public static int chance(int enhancement, int failures) {
		if (enhancement < 0 || enhancement >= 15 || failures < 0) throw new IllegalArgumentException("Invalid enhancement state.");
		var base=kr.dalmaru.content.ContentCatalog.current().chances();
        return (int)Math.min(100L, base.get(enhancement) + (long)failures * kr.dalmaru.content.ContentCatalog.current().enhancementPolicy().failureBonus());
	}
    public record Cost(int copper,int ironstone) { }
    public static Cost cost(int target) {
        // Higher prices require explicit entries in the versioned content.
        if(target<1||target>kr.dalmaru.content.ContentCatalog.current().costs().size())throw new IllegalArgumentException("Enhancement price is not defined for this tier.");
        return kr.dalmaru.content.ContentCatalog.current().costs().get(target-1);
    }
}
