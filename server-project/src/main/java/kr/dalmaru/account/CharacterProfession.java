package kr.dalmaru.account;

public enum CharacterProfession {
	WARRIOR("전사", "힘"), ARCHER("궁수", "재주"), MAGE("법사", "도력"), ROGUE("도적", "힘 또는 재주");
	private final String koreanName;
	private final String primaryStat;
	CharacterProfession(String koreanName, String primaryStat) { this.koreanName = koreanName; this.primaryStat = primaryStat; }
	public String koreanName() { return koreanName; }
	public String primaryStat() { return primaryStat; }
}
