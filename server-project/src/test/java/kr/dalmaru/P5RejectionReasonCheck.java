package kr.dalmaru;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.gather.Gathering;
import kr.dalmaru.request.RequestEnvelope;

public final class P5RejectionReasonCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a5c2");
	private static final Pattern REMAINING = Pattern.compile("Gather node is not ready\\. (\\d+) seconds remain\\.");
	private P5RejectionReasonCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		String networking = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
		String skill = between(networking, "private static void learnSkillRequest", "private static void acceptQuestRequest");
		String play = between(networking, "private static void playAccess", "private static com.google.gson.JsonObject villageList");
		String story = between(networking, "private static void storyOperation", "private static void grantCombatExperience");
		require(skill.contains("Unknown skill.") && skill.contains("rejectOperation(player,correlation,envelope.requestId(),error)"), "Skill rejection has no reason.");
		require(play.contains("rejectOperation(player,correlation,envelope.requestId(),error)"), "Gather rejection has no reason.");
		require(story.contains("rejectOperation(player,correlation,envelope.requestId(),error)"), "Story rejection has no reason.");
		String client = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruClient.java"));
		String access = Files.readString(Path.of("src/client/java/kr/dalmaru/client/ClientPlayAccessState.java"));
		String storyClient = Files.readString(Path.of("src/client/java/kr/dalmaru/client/ClientStoryState.java"));
		require(client.contains("기술을 습득할 수 없습니다.") && access.contains("\"reason\"") && storyClient.contains("rejectionText"), "Rejection text is not shown.");
		require(networking.contains("채집 지점이 아직 준비되지 않았습니다.") && networking.contains("알 수 없는 기술입니다.") && networking.contains("필요한 이야기를 마치지 않았습니다.") && networking.contains("이미 배운 생활 직업입니다.") && networking.contains("10레벨부터 직업을 고를 수 있습니다.") && networking.contains("제작 조건이 아직 맞지 않습니다."), "Player rejection text is still English.");
		FixtureMigrations.apply();
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var gathering = new Gathering();
		try {
			long generation = accounts.openSession(ACCOUNT).connectionGeneration();
			UUID lead = characters.create(ACCOUNT, 1, "달마루_거절갑").characterId();
			characters.selectActive(ACCOUNT, lead);
			take(gathering, generation, 1, lead);
			String message = "";
			try { take(gathering, generation, 2, lead); }
			catch (IllegalArgumentException expected) { message = expected.getMessage() == null ? "" : expected.getMessage(); }
			var match = REMAINING.matcher(message);
			require(match.matches(), "Cooldown rejection does not name the remaining seconds.");
			int seconds = Integer.parseInt(match.group(1));
			require(seconds >= 1 && seconds <= 180, "Cooldown remaining seconds are outside the iron wait.");
			System.out.println("Skill, gather cooldown, and story rejections carry a reason PASS");
		} finally {
			cleanup();
		}
	}

	private static String between(String source, String start, String end) {
		int from = source.indexOf(start);
		int to = source.indexOf(end, from + start.length());
		if (from < 0 || to < 0) throw new IllegalStateException(start);
		return source.substring(from, to);
	}

	private static Gathering.Journaled take(Gathering gathering, long generation, long request, UUID character) throws Exception {
		var body = new JsonObject();
		body.addProperty("nodeId", "gather.ironstone");
		body.addProperty("x", 999);
		return gathering.actJournaled(ACCOUNT, generation, request, character, "gather.ironstone", "minecraft:overworld", 200, 64, 200, RequestEnvelope.legacy(ACCOUNT, generation, character, "gather_node", request, body));
	}

	private static java.sql.Connection connection() throws Exception {
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv("DALMARU_DB_NAME"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void cleanup() throws Exception {
		try (var connection = connection()) {
			for (String query : new String[] {
				"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
				"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
				"DELETE FROM dalmaru.account_gather_cooldowns WHERE account_id=?",
				"DELETE FROM dalmaru.character_life_professions WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_life_xp WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_wallet WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.characters WHERE account_id=?",
				"DELETE FROM dalmaru.accounts WHERE account_id=?"
			}) try (var statement = connection.prepareStatement(query)) { statement.setObject(1, ACCOUNT); statement.executeUpdate(); }
		}
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
