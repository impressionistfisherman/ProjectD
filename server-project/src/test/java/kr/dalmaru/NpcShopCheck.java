package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.shop.NpcShop;
import kr.dalmaru.shop.ShopCatalog;

public final class NpcShopCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a533");
	private NpcShopCheck() { }

	public static void main(String[] args) throws Exception {
		if (!"grok_p3_fixture".equals(FixtureDatabase.databaseName())) throw new IllegalStateException("Refusing to run outside grok_p3_fixture.");
		require(ShopCatalog.buyPrice("supply.recovery") == 8 && ShopCatalog.buyPrice("material.ironstone") == 16 && ShopCatalog.sellPrice("material.ironstone") == 4, "Temporary shop price is wrong.");
		rejectPrice(() -> ShopCatalog.buyPrice("missing.item"));
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var shop = new NpcShop();
		try {
			var session = accounts.openSession(ACCOUNT);
			long generation = session.connectionGeneration();
			UUID character = characters.create(ACCOUNT, 1, "달마루_상점").characterId();
			sql("UPDATE dalmaru.character_wallet SET copper=20 WHERE character_id=?", character);
			characters.selectActive(ACCOUNT, character);
			reject(() -> trade(shop, generation, 1, character, "supply.recovery", 1, "buy", "minecraft:overworld", 1000, 64, 0));
			require(copper(character) == 20, "Rejected shop changed copper.");
			var bought = trade(shop, generation, 2, character, "supply.recovery", 1, "buy", "minecraft:overworld", 0, 64, 0);
			var replay = trade(shop, generation, 2, character, "supply.recovery", 1, "buy", "minecraft:overworld", 999, 64, 999);
			require(!bought.replayed() && replay.replayed() && copper(character) == 12 && material(character, "supply.recovery") == 5, "Recovery was not bought once.");
			reject(() -> trade(shop, generation, 3, character, "supply.recovery", 1, "sell", "minecraft:overworld", 0, 64, 0));
			require(material(character, "supply.recovery") == 5, "Granted recovery was sold.");
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.ironstone', 3, 'FREE')", character);
			trade(shop, generation, 4, character, "material.ironstone", 1, "sell", "minecraft:overworld", 0, 64, 0);
			require(copper(character) == 16 && material(character, "material.ironstone") == 2, "Ironstone sale price was wrong.");
			sql("UPDATE dalmaru.character_materials SET binding='CHARACTER', quantity=1 WHERE character_id=? AND material_id='material.ironstone'", character);
			reject(() -> trade(shop, generation, 5, character, "material.ironstone", 1, "sell", "minecraft:overworld", 0, 64, 0));
			require(material(character, "material.ironstone") == 1 && copper(character) == 16, "Character binding was sold.");
			sql("UPDATE dalmaru.character_wallet SET copper=0 WHERE character_id=?", character);
			reject(() -> trade(shop, generation, 6, character, "supply.recovery", 1, "buy", "minecraft:overworld", 0, 64, 0));
			require(copper(character) == 0, "Unpaid purchase changed copper.");
			System.out.println("NPC shop judges price, balance, and binding and ignores client coordinates PASS");
		} finally {
			cleanup();
		}
	}

	private static NpcShop.Journaled trade(NpcShop shop, long generation, long request, UUID character, String item, int quantity, String direction, String dimension, int x, int y, int z) throws Exception {
		var body = new JsonObject();
		body.addProperty("itemId", item);
		body.addProperty("quantity", quantity);
		body.addProperty("direction", direction);
		body.addProperty("x", 999);
		var envelope = RequestEnvelope.legacy(ACCOUNT, generation, character, "npc_shop", request, body);
		return shop.actJournaled(ACCOUNT, generation, request, character, item, quantity, direction, dimension, x, y, z, envelope);
	}

	private static void reject(Attempt attempt) {
		try { attempt.run(); }
		catch (IllegalArgumentException expected) { return; }
		catch (Exception exception) { throw new IllegalStateException(exception); }
		throw new IllegalStateException("Shop accepted a blocked trade.");
	}

	private static void rejectPrice(Attempt attempt) {
		try { attempt.run(); }
		catch (IllegalArgumentException expected) { return; }
		catch (Exception exception) { throw new IllegalStateException(exception); }
		throw new IllegalStateException("Missing shop item was priced.");
	}

	private static void sql(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) { statement.setObject(1, id); statement.executeUpdate(); }
	}

	private static int copper(UUID id) throws Exception { return scalar("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", id); }
	private static int material(UUID id, String item) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id=?")) {
			statement.setObject(1, id);
			statement.setString(2, item);
			try (var result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
		}
	}
	private static int scalar(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
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
				"DELETE FROM dalmaru.character_currency_ledger WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
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
