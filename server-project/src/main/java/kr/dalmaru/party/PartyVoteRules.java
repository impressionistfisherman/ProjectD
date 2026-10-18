package kr.dalmaru.party;

/** Quorum is the present members except the target. Abstain is not a yes. */
public final class PartyVoteRules {
	private PartyVoteRules() { }

	public static int requiredYes(int present) {
		int eligible = Math.max(0, present - 1);
		return eligible / 2 + 1;
	}
}
