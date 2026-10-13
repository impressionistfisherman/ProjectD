package kr.dalmaru;

import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.equipment.EquipmentRepository;

public final class CharacterEquipmentCheck {
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private CharacterEquipmentCheck() { }
    public static void main(String[] args) throws Exception {
        cleanup(ACCOUNT); cleanup(OTHER_ACCOUNT);
        var accounts = new AccountRepository(); var characters = new CharacterRepository(); var equipment = new EquipmentRepository();
        var session = accounts.openSession(ACCOUNT); var character = characters.create(ACCOUNT, 1, "달마루_장비시험11");
        characters.selectActive(ACCOUNT, session.connectionGeneration(), 1001, character.characterId());
        try(var connection=connection();var wallet=connection.prepareStatement("INSERT INTO dalmaru.character_wallet(character_id,copper) VALUES(?,100) ON CONFLICT(character_id) DO UPDATE SET copper=100")){wallet.setObject(1,character.characterId());wallet.executeUpdate();}
        var items = equipment.listOrCreate(character.characterId());
        if (items.size() != 9 || !items.stream().allMatch(item -> item.bound())) throw new IllegalStateException("Nine bound starter items were not created.");
        var first = equipment.enhance(ACCOUNT, session.connectionGeneration(), 2001, items.getFirst().equipmentId());
        var replay = equipment.enhance(ACCOUNT, session.connectionGeneration(), 2001, items.getFirst().equipmentId());
        if (!replay.replayed() || first.success() != replay.success() || first.equipment().enhancement() != replay.equipment().enhancement()) throw new IllegalStateException("Enhancement replay changed its result.");
        var otherSession = accounts.openSession(OTHER_ACCOUNT); var otherCharacter = characters.create(OTHER_ACCOUNT, 1, "달마루_장비시험12");
        characters.selectActive(OTHER_ACCOUNT, otherSession.connectionGeneration(), 1002, otherCharacter.characterId());
        boolean rejected = false; try { equipment.enhance(ACCOUNT, session.connectionGeneration(), 2002, equipment.listOrCreate(otherCharacter.characterId()).getFirst().equipmentId()); } catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new IllegalStateException("Foreign equipment was accepted.");
        try (var connection = connection(); var update = connection.prepareStatement("UPDATE dalmaru.character_equipment SET enhancement=15 WHERE equipment_id=?")) { update.setObject(1, items.get(1).equipmentId()); update.executeUpdate(); }
        rejected = false; try { equipment.enhance(ACCOUNT, session.connectionGeneration(), 2003, items.get(1).equipmentId()); } catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new IllegalStateException("Maximum enhancement was accepted.");
        cleanup(ACCOUNT); cleanup(OTHER_ACCOUNT); System.out.println("Character equipment verified.");
    }
    private static void cleanup(UUID accountId) throws Exception { if (System.getenv("DALMARU_DB_PASSWORD") == null || System.getenv("DALMARU_DB_PASSWORD").isBlank()) return; try (var connection = connection()) { for (String sql : new String[] { "UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?", "DELETE FROM dalmaru.character_enhancement_requests WHERE account_id=?", "DELETE FROM dalmaru.character_equipment WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.character_selection_requests WHERE account_id=?", "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id=?)", "DELETE FROM dalmaru.characters WHERE account_id=?", "DELETE FROM dalmaru.accounts WHERE account_id=?" }) try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, accountId); statement.executeUpdate(); } } }
    private static java.sql.Connection connection() throws Exception { Class.forName("org.postgresql.Driver"); return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD")); }
}
