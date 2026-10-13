package kr.dalmaru.run;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persists the personal portion of a completed run while its row is locked. */
public final class RunRewardRepository {
    public List<RunReward> grant(Connection connection, UUID runId, RunDifficulty difficulty) throws SQLException {
        List<RunReward> rewards = new ArrayList<>();
        try (var participants = connection.prepareStatement("SELECT character_id FROM dalmaru.run_participants WHERE run_id=? FOR UPDATE")) {
            participants.setObject(1, runId);
            try (var rows = participants.executeQuery()) {
                while (rows.next()) rewards.add(grant(connection, runId, rows.getObject(1, UUID.class), difficulty));
            }
        }
        return rewards;
    }

    public List<RunReward> rewards(Connection connection, UUID runId) throws SQLException {
        List<RunReward> rewards = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT character_id, copper, ironstone, beacon_token, equipment_box FROM dalmaru.run_reward_grants WHERE run_id=?")) {
            statement.setObject(1, runId);
            try (var rows = statement.executeQuery()) { while (rows.next()) rewards.add(new RunReward(rows.getObject(1, UUID.class), rows.getInt(2), rows.getInt(3), rows.getInt(4), rows.getString(5))); }
        }
        return rewards;
    }

    private RunReward grant(Connection connection, UUID runId, UUID characterId, RunDifficulty difficulty) throws SQLException {
        RunReward existing = existing(connection, runId, characterId); if (existing != null) return existing;
        boolean first = firstClear(connection, characterId, difficulty);
        int copper = difficulty == RunDifficulty.FIRST_STEP ? 70 : 100;
        int ironstone = difficulty == RunDifficulty.FIRST_STEP ? 3 : 5;
        int beaconToken = difficulty == RunDifficulty.FIRST_STEP ? 1 : 2;
        String box = first ? difficulty == RunDifficulty.FIRST_STEP ? "equipment_box.level_15" : "equipment_box.level_20" : null;
        try (var grant = connection.prepareStatement("INSERT INTO dalmaru.run_reward_grants VALUES (?, ?, ?, ?, ?, ?)")) { grant.setObject(1, runId); grant.setObject(2, characterId); grant.setInt(3, copper); grant.setInt(4, ironstone); grant.setInt(5, beaconToken); grant.setString(6, box); grant.executeUpdate(); }
        try (var wallet = connection.prepareStatement("INSERT INTO dalmaru.character_wallet (character_id, copper) VALUES (?, ?) ON CONFLICT (character_id) DO UPDATE SET copper=dalmaru.character_wallet.copper+EXCLUDED.copper")) { wallet.setObject(1, characterId); wallet.setInt(2, copper); wallet.executeUpdate(); }
        try (var ledger = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger (event_id, character_id, source_run_id, reason, copper_delta) VALUES (?, ?, ?, 'run.clear', ?)")) { ledger.setObject(1, UUID.randomUUID()); ledger.setObject(2, characterId); ledger.setObject(3, runId); ledger.setInt(4, copper); ledger.executeUpdate(); }
        material(connection, characterId, "material.ironstone", ironstone); material(connection, characterId, "token.bongsu", beaconToken);
        if (box != null) try (var mailbox = connection.prepareStatement("INSERT INTO dalmaru.character_mailbox (event_id, character_id, source_run_id, item_id, quantity) VALUES (?, ?, ?, ?, 1)")) { mailbox.setObject(1, UUID.randomUUID()); mailbox.setObject(2, characterId); mailbox.setObject(3, runId); mailbox.setString(4, box); mailbox.executeUpdate(); }
        kr.dalmaru.account.CharacterRepository.grantExperience(connection, characterId, 500);
        return new RunReward(characterId, copper, ironstone, beaconToken, box);
    }

    private boolean firstClear(Connection connection, UUID characterId, RunDifficulty difficulty) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO dalmaru.character_run_first_rewards VALUES (?, ?) ON CONFLICT DO NOTHING")) { statement.setObject(1, characterId); statement.setString(2, difficulty.name()); return statement.executeUpdate() == 1; }
    }

    private void material(Connection connection, UUID characterId, String materialId, int quantity) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO dalmaru.character_materials (character_id, material_id, quantity) VALUES (?, ?, ?) ON CONFLICT (character_id, material_id) DO UPDATE SET quantity=dalmaru.character_materials.quantity+EXCLUDED.quantity")) { statement.setObject(1, characterId); statement.setString(2, materialId); statement.setInt(3, quantity); statement.executeUpdate(); }
    }

    private RunReward existing(Connection connection, UUID runId, UUID characterId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT copper, ironstone, beacon_token, equipment_box FROM dalmaru.run_reward_grants WHERE run_id=? AND character_id=?")) { statement.setObject(1, runId); statement.setObject(2, characterId); try (var rows = statement.executeQuery()) { return rows.next() ? new RunReward(characterId, rows.getInt(1), rows.getInt(2), rows.getInt(3), rows.getString(4)) : null; } }
    }
}
