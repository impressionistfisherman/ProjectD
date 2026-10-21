package kr.dalmaru;

import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;

public final class CharacterSelectionCheck {
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

	private CharacterSelectionCheck() { }

	public static void main(String[] args) throws Exception {
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		try {
			var session = accounts.openSession(ACCOUNT_ID);
			var first = characters.create(ACCOUNT_ID, 1, "달마루_선택시험4A");
			var second = characters.create(ACCOUNT_ID, 2, "달마루_선택시험4B");
			var selected = characters.selectActive(ACCOUNT_ID, session.connectionGeneration(), 101, first.characterId());
			if (!initialSpawn(selected.vanillaState())) throw new AssertionError("First character needs a village arrival.");
			if (selected.replayed()) throw new IllegalStateException("Initial selection was treated as replay.");
			if (!characters.selectActive(ACCOUNT_ID, session.connectionGeneration(), 101, first.characterId()).replayed()) throw new IllegalStateException("Repeated request was not replayed.");
			try {
				characters.selectActive(ACCOUNT_ID, session.connectionGeneration(), 101, second.characterId());
				throw new IllegalStateException("Changed request body was accepted.");
			} catch (IllegalArgumentException expected) { }
			try {
				characters.selectActive(ACCOUNT_ID, session.connectionGeneration() - 1, 102, second.characterId());
				throw new IllegalStateException("Stale connection generation was accepted.");
			} catch (IllegalArgumentException expected) { }
            int[] installed={0};
            characters.selectActive(ACCOUNT_ID,session.connectionGeneration(),101,first.characterId(),null,state->installed[0]++);
            if(installed[0]!=0)throw new AssertionError("Selection replay repeated the initial arrival.");
            String firstSnapshot=arrived(selected.vanillaState());
            try {
                characters.selectActive(ACCOUNT_ID,session.connectionGeneration(),103,second.characterId(),firstSnapshot,state->{throw new IllegalStateException("arrival failed");});
                throw new AssertionError("Failed initial arrival committed.");
            } catch(IllegalStateException expected) {
                if(!"arrival failed".equals(expected.getMessage()))throw expected;
            }
            var next=characters.selectActive(ACCOUNT_ID,session.connectionGeneration(),103,second.characterId(),firstSnapshot,state->installed[0]++);
            if(next.replayed()||!initialSpawn(next.vanillaState())||installed[0]!=1)throw new AssertionError("New slot arrival was lost after rollback.");
            var returned=characters.selectActive(ACCOUNT_ID,session.connectionGeneration(),104,first.characterId(),arrived(next.vanillaState()),state->installed[0]++);
            if(initialSpawn(returned.vanillaState())||installed[0]!=2)throw new AssertionError("Existing character must keep its location.");
			if (!accounts.openSession(ACCOUNT_ID).activeCharacterId().equals(first.characterId())) throw new IllegalStateException("Active character was not preserved.");
			System.out.println("Character selection idempotency verified.");
		} finally {
			cleanup();
		}
	}

    private static boolean initialSpawn(String snapshot) {
        var json=com.google.gson.JsonParser.parseString(snapshot).getAsJsonObject();
        return json.has("initial_spawn")&&json.get("initial_spawn").getAsBoolean();
    }

    private static String arrived(String snapshot) {
        var json=com.google.gson.JsonParser.parseString(snapshot).getAsJsonObject();
        json.remove("initial_spawn");
        return json.toString();
    }

	private static void cleanup() throws Exception {
		String password = System.getenv("DALMARU_DB_PASSWORD");
		if (password == null || password.isBlank()) return;
		Class.forName("org.postgresql.Driver");
		try (var connection = java.sql.DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), password)) {
			execute(connection, "UPDATE dalmaru.accounts SET active_character_id = NULL WHERE account_id = ?");
			execute(connection, "DELETE FROM dalmaru.character_selection_requests WHERE account_id = ?");
			execute(connection, "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id = ?)");
			execute(connection, "DELETE FROM dalmaru.characters WHERE account_id = ?");
			execute(connection, "DELETE FROM dalmaru.accounts WHERE account_id = ?");
		}
	}

	private static void execute(java.sql.Connection connection, String sql) throws Exception {
		try (var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, ACCOUNT_ID);
			statement.executeUpdate();
		}
	}
}
