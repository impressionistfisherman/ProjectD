package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

public final class ApplyCurrentMigrations {
	private ApplyCurrentMigrations() { }
	public static void main(String[] args) throws Exception {
		String password = System.getenv("DALMARU_DB_PASSWORD");
		if (password == null || password.isBlank()) throw new IllegalStateException("DALMARU_DB_PASSWORD is not configured.");
		Class.forName("org.postgresql.Driver");
		try (var connection = DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), password)) {
			for (int version : new int[] { 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20 }) {
				try (var check = connection.prepareStatement("SELECT 1 FROM dalmaru.schema_migrations WHERE version = ?")) {
					check.setInt(1, version);
					try (var result = check.executeQuery()) { if (result.next()) continue; }
				}
            String file = version == 9 ? "V009__character_skill_points.sql" : version == 10 ? "V010__character_skill_ranks.sql" : version == 11 ? "V011__character_equipment.sql" : version == 12 ? "V012__run_reservations.sql" : version == 13 ? "V013__run_rewards.sql" : version == 14 ? "V014__currency_ledger.sql" : version == 15 ? "V015__run_lifecycle.sql" : version == 16 ? "V016__equipment_refinement.sql" : version == 17 ? "V017__currency_ledger_source_optional.sql" : version == 18 ? "V018__equipment_storage.sql" : version == 19 ? "V019__equipment_inheritance.sql" : "V020__equipment_slot_conflict.sql";
				try (var statement = connection.createStatement()) { statement.execute(Files.readString(Path.of("database", "migrations", file))); }
			}
		}
		System.out.println("Current Dalmaru migrations applied.");
	}
}
