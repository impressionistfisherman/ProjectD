package kr.dalmaru;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.AccountVault;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class AccountVaultCheck {
	private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000a532");
	private AccountVaultCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		applyMigration();
		cleanup();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		var vault = new AccountVault();
		try {
			var session = accounts.openSession(ACCOUNT);
			long generation = session.connectionGeneration();
			UUID lead = characters.create(ACCOUNT, 1, "달마루_금고").characterId();
			UUID partner = characters.create(ACCOUNT, 2, "달마루_창고").characterId();
			sql("UPDATE dalmaru.character_wallet SET copper=20 WHERE character_id=?", lead);
			sql("UPDATE dalmaru.character_wallet SET copper=20 WHERE character_id=?", partner);
			sql("UPDATE dalmaru.character_materials SET quantity=4, binding='FREE' WHERE character_id=? AND material_id='supply.recovery'", lead);
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.ironstone', 5, 'FREE')", lead);
			sql("INSERT INTO dalmaru.character_materials(character_id, material_id, quantity, binding) VALUES(?, 'material.seal', 1, 'CHARACTER')", partner);
			characters.selectActive(ACCOUNT, lead);
			var deposited = move(vault, generation, 1, lead, AccountVault.COPPER, 10, "deposit");
			var replay = move(vault, generation, 1, lead, AccountVault.COPPER, 10, "deposit");
			require(!deposited.replayed() && replay.replayed() && copper(lead) == 10 && holding(AccountVault.COPPER) == 10 && ledger(lead) == 1, "Copper deposit did not stay on the account once.");
			reject(() -> move(vault, generation, 2, lead, AccountVault.COPPER, 100, "deposit"));
			require(copper(lead) == 10 && holding(AccountVault.COPPER) == 10, "Rejected deposit changed copper.");
			reject(() -> move(vault, generation, 3, lead, "supply.recovery", 1, "deposit"));
			require(material(lead, "supply.recovery") == 4 && holding("supply.recovery") == 0, "Granted recovery entered the vault.");
			move(vault, generation, 4, lead, "material.ironstone", 2, "deposit");
			require(material(lead, "material.ironstone") == 3 && holding("material.ironstone") == 2, "Free material did not move into the vault.");
			characters.selectActive(ACCOUNT, partner);
			reject(() -> move(vault, generation, 5, partner, "material.seal", 1, "deposit"));
			require(material(partner, "material.seal") == 1 && holding("material.seal") == 0, "Character binding entered the vault.");
			move(vault, generation, 6, partner, AccountVault.COPPER, 4, "withdraw");
			move(vault, generation, 7, partner, "material.ironstone", 2, "withdraw");
			require(copper(lead) == 10 && copper(partner) == 24 && holding(AccountVault.COPPER) == 6, "Account copper did not follow the other character.");
			require(material(partner, "material.ironstone") == 2 && binding(partner, "material.ironstone").equals("FREE") && holding("material.ironstone") == 0, "Withdrawn material did not keep its binding.");
			System.out.println("Account vault keeps copper and materials across characters and blocks bound supply PASS");
		} finally {
			cleanup();
		}
	}

	private static AccountVault.Journaled move(AccountVault vault, long generation, long request, UUID character, String asset, int quantity, String direction) throws Exception {
		var body = new JsonObject();
		body.addProperty("assetId", asset);
		body.addProperty("quantity", quantity);
		body.addProperty("direction", direction);
		var envelope = RequestEnvelope.legacy(ACCOUNT, generation, character, "account_vault", request, body);
		return vault.actJournaled(ACCOUNT, generation, request, character, asset, quantity, direction, envelope);
	}

	private static void applyMigration() throws Exception {
		try (var connection = connection(); var check = connection.prepareStatement("SELECT 1 FROM dalmaru.schema_migrations WHERE version=32")) {
			try (var result = check.executeQuery()) { if (result.next()) return; }
			try (var statement = connection.createStatement()) { statement.execute(Files.readString(Path.of("database", "migrations", "V032__account_holdings.sql"))); }
		}
	}

	private static void reject(Attempt attempt) {
		try { attempt.run(); }
		catch (IllegalArgumentException expected) { return; }
		catch (Exception exception) { throw new IllegalStateException(exception); }
		throw new IllegalStateException("Vault accepted a blocked move.");
	}

	private static void sql(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) { statement.setObject(1, id); statement.executeUpdate(); }
	}

	private static int copper(UUID id) throws Exception { return scalar("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", id); }
	private static int material(UUID id, String asset) throws Exception { return scalar("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='" + asset + "'", id); }
	private static String binding(UUID id, String asset) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT binding FROM dalmaru.character_materials WHERE character_id=? AND material_id=?")) {
			statement.setObject(1, id);
			statement.setString(2, asset);
			try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
		}
	}
	private static int holding(String asset) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT quantity FROM dalmaru.account_holdings WHERE account_id=? AND asset_id=?")) {
			statement.setObject(1, ACCOUNT);
			statement.setString(2, asset);
			try (var result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
		}
	}
	private static int ledger(UUID id) throws Exception { return scalar("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE character_id=? AND reason='vault.deposit'", id); }
	private static int scalar(String query, UUID id) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement(query)) {
			statement.setObject(1, id);
			try (var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}
	private static java.sql.Connection connection() throws Exception {
		Class.forName("org.postgresql.Driver");
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv("DALMARU_DB_NAME"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}
	private static void cleanup() throws Exception {
		try (var connection = connection()) {
			for (String query : new String[] {
				"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
				"DELETE FROM dalmaru.operation_requests WHERE account_id=?",
				"DELETE FROM dalmaru.account_holdings WHERE account_id=?",
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
