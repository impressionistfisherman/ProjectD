package kr.dalmaru.party;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;

public final class PartyRules {
	private final boolean temporary;
	private final int protocol;
	private final int maxMembers;
	private final boolean recruitOnlyWhileReady;
	private final int voteMinPresent;
	private final int voteExpireSeconds;
	private final int revoteWaitSeconds;
	private final int reconnectSeconds;

	private PartyRules(boolean temporary, int protocol, int maxMembers, boolean recruitOnlyWhileReady, int voteMinPresent, int voteExpireSeconds, int revoteWaitSeconds, int reconnectSeconds) {
		this.temporary = temporary;
		this.protocol = protocol;
		this.maxMembers = maxMembers;
		this.recruitOnlyWhileReady = recruitOnlyWhileReady;
		this.voteMinPresent = voteMinPresent;
		this.voteExpireSeconds = voteExpireSeconds;
		this.revoteWaitSeconds = revoteWaitSeconds;
		this.reconnectSeconds = reconnectSeconds;
	}

	public static PartyRules current() {
		try (var stream = PartyRules.class.getResourceAsStream("/content/party.json")) {
			if (stream == null) throw new IllegalStateException("Missing party rules");
			JsonObject json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			var rules = new PartyRules(json.get("temporary").getAsBoolean(), json.get("protocol").getAsInt(), json.get("maxMembers").getAsInt(), json.get("recruitOnlyWhileReady").getAsBoolean(), json.get("voteMinPresent").getAsInt(), json.get("voteExpireSeconds").getAsInt(), json.get("revoteWaitSeconds").getAsInt(), json.get("reconnectSeconds").getAsInt());
			if (!rules.temporary || rules.protocol != 34 || rules.maxMembers != 4 || !rules.recruitOnlyWhileReady || rules.voteMinPresent != 3 || rules.voteExpireSeconds != 30 || rules.revoteWaitSeconds != 60 || rules.reconnectSeconds != 180) throw new IllegalStateException("Party rules do not match the approved defaults");
			return rules;
		} catch (java.io.IOException exception) {
			throw new IllegalStateException("Cannot load party rules", exception);
		}
	}

	public boolean temporary() { return temporary; }
	public int protocol() { return protocol; }
	public int maxMembers() { return maxMembers; }
	public boolean recruitOnlyWhileReady() { return recruitOnlyWhileReady; }
	public int voteMinPresent() { return voteMinPresent; }
	public int voteExpireSeconds() { return voteExpireSeconds; }
	public int revoteWaitSeconds() { return revoteWaitSeconds; }
	public int reconnectSeconds() { return reconnectSeconds; }
}
