package kr.dalmaru;

import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunRepository;

/** Reproduces entry cancellation racing with disconnect pause in both database orders. */
public final class RunEntryCancellationCheck {
    private RunEntryCancellationCheck() { }

    public static void main(String[] args) throws Exception {
        if (!"oscar_fixture".equals(System.getenv("DALMARU_DB_NAME"))) throw new IllegalStateException("Disposable database required.");
        UUID account = UUID.randomUUID();
        try {
            var accounts = new AccountRepository();
            var characters = new CharacterRepository();
            var runs = new RunRepository();
            var session = accounts.openSession(account);
            var character = characters.create(account, 1, "race_" + account.toString().substring(0, 8));
            characters.selectActive(account, session.connectionGeneration(), 1, character.characterId());
            characters.grantExperience(character.characterId(), 8000);
            String networking = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
            require(networking.contains(".whenComplete((ignored,failure)->context.server().execute(()->RUN_RUNTIME.close(context.server(),run.runId())))"), "N2 runtime close must run even if database cancellation fails or changes no rows");

            var cancelFirst = runs.enter(account, session.connectionGeneration(), 10, RunDifficulty.FIRST_STEP, List.of(character.characterId()));
            require(runs.cancelNewEntry(cancelFirst.runId(), account, session.connectionGeneration(), 10), "cancel first did not cancel active entry");
            runs.pauseIfUnattended(character.characterId(), List.of());
            require("FAILED".equals(state(cancelFirst.runId())), "cancel then disconnect left a reservation active/paused");

            var disconnectFirst = runs.enter(account, session.connectionGeneration(), 11, RunDifficulty.FIRST_STEP, List.of(character.characterId()));
            require("PAUSED".equals(runs.pauseIfUnattended(character.characterId(), List.of()).state()), "disconnect did not pause entry reservation");
            var reconnected = accounts.openSession(account);
            require(!runs.cancelNewEntry(disconnectFirst.runId(), account, reconnected.connectionGeneration(), 11), "new generation cancelled a different reservation tuple");
            require(runs.cancelNewEntry(disconnectFirst.runId(), account, session.connectionGeneration(), 11), "cancel second did not cancel paused entry after reconnect");
            require("FAILED".equals(state(disconnectFirst.runId())), "disconnect then cancel left a paused reservation");
            require(reconnected.connectionGeneration() != session.connectionGeneration(), "fixture did not advance connection generation");
            System.out.println("N1 both cancellation orders/reconnect reservation and N2 unconditional cleanup guard PASS");
        } finally {
            cleanup(account);
        }
    }

    private static String state(UUID run) throws Exception {
        try (var connection = connection(); var query = connection.prepareStatement("SELECT state FROM dalmaru.run_instances WHERE run_id=?")) {
            query.setObject(1, run);
            try (var row = query.executeQuery()) { if (!row.next()) throw new AssertionError("run missing"); return row.getString(1); }
        }
    }

    private static void cleanup(UUID account) throws Exception {
        try (var connection = connection()) {
            for (String sql : new String[]{
                "UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",
                "DELETE FROM dalmaru.run_requests WHERE account_id=?",
                "DELETE FROM dalmaru.run_instances WHERE run_id IN (SELECT p.run_id FROM dalmaru.run_participants p JOIN dalmaru.characters c ON c.character_id=p.character_id WHERE c.account_id=?)",
                "DELETE FROM dalmaru.character_run_weekly_clears WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
                "DELETE FROM dalmaru.character_selection_requests WHERE account_id=?",
                "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)",
                "DELETE FROM dalmaru.characters WHERE account_id=?",
                "DELETE FROM dalmaru.accounts WHERE account_id=?"
            }) try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, account); statement.executeUpdate(); }
        }
    }

    private static java.sql.Connection connection() throws Exception {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
