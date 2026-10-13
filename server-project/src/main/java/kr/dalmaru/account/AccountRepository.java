package kr.dalmaru.account;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

public final class AccountRepository {
	static {
		try {
			Class.forName("org.postgresql.Driver");
		} catch (ClassNotFoundException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}

	private final String url;
	private final String username;
	private final String password;

	public AccountRepository() {
		String host = System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost");
		String port = System.getenv().getOrDefault("DALMARU_DB_PORT", "15432");
		String database = System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
		this.url = "jdbc:postgresql://" + host + ":" + port + "/" + database + "?connectTimeout=1&loginTimeout=1&socketTimeout=2&options=-c%20statement_timeout%3D250ms%20-c%20lock_timeout%3D200ms";
		this.username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
		this.password = System.getenv("DALMARU_DB_PASSWORD");
	}

	public AccountSession openSession(UUID accountId) throws SQLException {
		if (password == null || password.isBlank()) {
			throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		}
		String sql = """
			INSERT INTO dalmaru.accounts (account_id, connection_generation)
			VALUES (?, 1)
			ON CONFLICT (account_id) DO UPDATE
			SET connection_generation = dalmaru.accounts.connection_generation + 1
			RETURNING account_id, active_character_id, connection_generation
			""";
		try (var connection = DriverManager.getConnection(url, username, password);
			 var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, accountId);
			try (var result = statement.executeQuery()) {
				result.next();
				return new AccountSession(
					(UUID) result.getObject("account_id"),
					(UUID) result.getObject("active_character_id"),
					result.getLong("connection_generation"));
			}
		}
	}
}
