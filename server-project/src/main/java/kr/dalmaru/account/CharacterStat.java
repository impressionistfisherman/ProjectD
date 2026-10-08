package kr.dalmaru.account;

public enum CharacterStat {
	STRENGTH(0), DEXTERITY(1), ARCANA(2), VITALITY(3), SPIRIT(4);

	private final int id;

	CharacterStat(int id) { this.id = id; }

	public int id() { return id; }

	public static CharacterStat fromId(int id) {
		for (CharacterStat stat : values()) if (stat.id == id) return stat;
		throw new IllegalArgumentException("Unknown character stat.");
	}
}
