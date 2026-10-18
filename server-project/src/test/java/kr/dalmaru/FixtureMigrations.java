package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Applies existing V038, V039 and V040 when this fixture stopped at V037. Does not add a migration. */
public final class FixtureMigrations {
	private FixtureMigrations() { }

	public static void apply() throws Exception {
		apply(38, "V038__run_auctions.sql");
		apply(39, "V039__mail_claim_ledger_events.sql");
		apply(40, "V040__run_member_lifecycle.sql");
	}

	private static void apply(int version, String name) throws Exception {
		try (var connection = EnhancementReplayCheck.connection(); var check = connection.prepareStatement("SELECT 1 FROM dalmaru.schema_migrations WHERE version=?")) {
			check.setInt(1, version);
			try (var result = check.executeQuery()) { if (result.next()) return; }
		}
		String sql = stripLineComments(Files.readString(Path.of("database", "migrations", name)));
		try (var connection = EnhancementReplayCheck.connection()) {
			connection.setAutoCommit(false);
			try {
				for (String statement : split(sql)) try (var command = connection.createStatement()) { command.execute(statement); }
				connection.commit();
			} catch (SQLException error) { connection.rollback(); throw error; }
		}
	}

	private static String stripLineComments(String sql) {
		var plain = new StringBuilder();
		boolean dollar = false;
		for (int i = 0; i < sql.length(); i++) {
			if (sql.startsWith("$$", i)) { dollar = !dollar; plain.append("$$"); i++; continue; }
			if (!dollar && sql.startsWith("--", i)) {
				int newline = sql.indexOf('\n', i);
				if (newline < 0) break;
				i = newline;
				plain.append('\n');
				continue;
			}
			plain.append(sql.charAt(i));
		}
		return plain.toString();
	}

	private static List<String> split(String sql) {
		var statements = new ArrayList<String>();
		var current = new StringBuilder();
		boolean dollar = false;
		for (int i = 0; i < sql.length(); i++) {
			if (sql.startsWith("$$", i)) { dollar = !dollar; current.append("$$"); i++; continue; }
			char letter = sql.charAt(i);
			if (!dollar && letter == ';') {
				String statement = current.toString().trim();
				if (!statement.isEmpty() && !statement.equalsIgnoreCase("BEGIN") && !statement.equalsIgnoreCase("COMMIT")) statements.add(statement);
				current.setLength(0);
			} else current.append(letter);
		}
		return statements;
	}
}
