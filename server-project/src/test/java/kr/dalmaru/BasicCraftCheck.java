package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.craft.BasicCraft;
import kr.dalmaru.craft.CraftCatalog;
import kr.dalmaru.request.RequestEnvelope;

public final class BasicCraftCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a535");
	private BasicCraftCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		reject(() -> CraftCatalog.recipe("missing.recipe"));
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var craft = new BasicCraft();
		try {
			long generation = accounts.openSession(ACCOUNT).connectionGeneration();
			UUID character = characters.create(ACCOUNT, 1, "달마루_제작").characterId();
			characters.selectActive(ACCOUNT, character);
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.ironstone', 1, 'FREE')", character);
			var made = make(craft, generation, 1, character, "craft.iron_ingot", 1000, 64, 0);
			var replay = make(craft, generation, 1, character, "craft.iron_ingot", 1000, 64, 0);
			require(!made.replayed() && replay.replayed() && material(character, "material.ironstone") == 0 && material(character, "material.iron_ingot") == 1, "Ore was not processed once.");
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.ironstone', 1, 'CHARACTER')", character);
			reject(() -> make(craft, generation, 2, character, "craft.iron_ingot", 1000, 64, 0));
			require(material(character, "material.ironstone") == 1 && binding(character, "material.ironstone").equals("CHARACTER"), "Character-bound ore was processed.");
			reject(() -> make(craft, generation, 3, character, "craft.basic_fitting", 1000, 64, 0));
			require(material(character, "material.iron_ingot") == 1 && material(character, "material.basic_fitting") == 0, "Forge craft succeeded away from the forge.");
			make(craft, generation, 4, character, "craft.basic_fitting", 16, 64, 16);
			require(material(character, "material.iron_ingot") == 0 && material(character, "material.basic_fitting") == 1 && binding(character, "material.basic_fitting").equals("FREE"), "Forge craft did not spend the ingot.");
			System.out.println("Basic craft processes ore anywhere and fits only at the forge PASS");
		} finally {
			cleanup();
		}
	}

	private static BasicCraft.Journaled make(BasicCraft craft, long generation, long request, UUID character, String recipe, int x, int y, int z) throws Exception {
		var body = new JsonObject();
		body.addProperty("recipeId", recipe);
		body.addProperty("x", 999);
		var envelope = RequestEnvelope.legacy(ACCOUNT, generation, character, "basic_craft", request, body);
		return craft.actJournaled(ACCOUNT, generation, request, character, recipe, "minecraft:overworld", x, y, z, envelope);
	}

	private static void reject(Attempt attempt) {
		try { attempt.run(); }
		catch (IllegalArgumentException expected) { return; }
		catch (Exception exception) { throw new IllegalStateException(exception); }
		throw new IllegalStateException("Craft accepted a blocked request.");
	}

	private static void sql(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) { statement.setObject(1, id); statement.executeUpdate(); }
	}

	private static int material(UUID id, String item) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id=?")) {
			statement.setObject(1, id);
			statement.setString(2, item);
			try (var result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
		}
	}

	private static String binding(UUID id, String item) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT binding FROM dalmaru.character_materials WHERE character_id=? AND material_id=?")) {
			statement.setObject(1, id);
			statement.setString(2, item);
			try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
		}
	}

	private static java.sql.Connection connection() throws Exception {
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv("DALMARU_DB_NAME"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}

	private static void cleanup() throws Exception {
		try (var connection = connection()) {
			for (String query : new String[] {
				"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
				"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
				"DELETE FROM dalmaru.character_materials WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_wallet WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
				"DELETE FROM dalmaru.characters WHERE account_id=?",
				"DELETE FROM dalmaru.accounts WHERE account_id=?"
			}) try (var statement = connection.prepareStatement(query)) { statement.setObject(1, ACCOUNT); statement.executeUpdate(); }
		}
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
	@FunctionalInterface private interface Attempt { void run() throws Exception; }
}
