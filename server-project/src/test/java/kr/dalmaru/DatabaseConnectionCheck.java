package kr.dalmaru;

import java.sql.DriverManager;

public final class DatabaseConnectionCheck {
	private DatabaseConnectionCheck() { }

	public static void main(String[] args) throws Exception {
		String host = System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost");
		String port = System.getenv().getOrDefault("DALMARU_DB_PORT", "15432");
		String database = System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
		String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
		String password = System.getenv("DALMARU_DB_PASSWORD");
		if (password == null || password.isBlank()) {
			throw new IllegalStateException("DALMARU_DB_PASSWORD is required for the database connection check.");
		}

		String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
		try (var connection = DriverManager.getConnection(url, username, password);
			 var statement = connection.createStatement();
			 var result = statement.executeQuery("SELECT current_database(), version()")) {
			if (!result.next()) {
				throw new IllegalStateException("Database connection check returned no row.");
			}
			System.out.println("Connected to " + result.getString(1) + " using " + result.getString(2));
		}
	}
}
