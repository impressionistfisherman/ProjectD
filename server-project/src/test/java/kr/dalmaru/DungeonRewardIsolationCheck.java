package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterProfile;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.combat.CombatRewards;
import kr.dalmaru.run.AuctionRepository;
import kr.dalmaru.run.RunContribution.Action;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunMechanicAccess.ExitReason;
import kr.dalmaru.run.RunMechanicAccess.ExitStatus;
import kr.dalmaru.run.RunMemberExit;
import kr.dalmaru.run.RunMemberService;
import kr.dalmaru.run.RunRepository;
import kr.dalmaru.run.RunReward;

/**
 * P4-S10 reward isolation. Dungeon monsters and both bosses never drop vanilla loot/XP or count for field quests; the
 * only dungeon reward is the personal P3 grant, and members who left (voluntary, kick, reconnect expiry) get none of
 * it: no wallet copper, ledger row, material, mail, XP, weekly clear or auction seat. Replays change nothing.
 */
public final class DungeonRewardIsolationCheck {
    private DungeonRewardIsolationCheck() { }

    public static void main(String[] args) throws Exception {
        // 1. Vanilla loot, XP and field quest bypass.
        check(CombatRewards.excludesRewardTags(Set.of("dalmaru.mob.guard")) && CombatRewards.excludesRewardTags(Set.of("dalmaru.mob.boss")) && CombatRewards.excludesRewardTags(Set.of("dalmaru.mob.linger")), "A dungeon tag is not reward-excluded");
        check(!CombatRewards.excludesRewardTags(Set.of("dalmaru.run.other")), "Field mobs lost their rewards");
        String runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        String bossSpawn = method(runtime, "private static UUID spawnBoss(");
        check(bossSpawn.contains("addTag(\"dalmaru.mob.boss\")"), "Dungeon bosses are not tagged reward-excluded (vanilla loot/XP/quest bypass)");
        check(method(runtime, "private void spawnMob(").contains("addTag(\"dalmaru.mob.\"+kind.name()"), "Dungeon mobs are not tagged reward-excluded");
        check(runtime.contains("body.addTag(\"dalmaru.mob.linger\")"), "Disconnected-player bodies are not reward-excluded");
        check(count(runtime, "EntityType.ZOMBIE.create(") == 1 && count(runtime, "EntityType.HUSK.create(") == 2, "A new dungeon entity spawn path appeared without a reward-exclusion check");
        String mixin = Files.readString(Path.of("src/main/java/kr/dalmaru/mixin/DungeonRewardMixin.java"));
        check(mixin.contains("\"dropAllDeathLoot\"") && mixin.contains("\"dropExperience\"") && mixin.contains("excludesRewardTags"), "Vanilla loot/XP mixin does not cover both drops");
        check(Files.readString(Path.of("src/main/resources/dalmaru.mixins.json")).contains("\"DungeonRewardMixin\""), "Reward mixin is not registered");
        String networking = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        check(networking.contains("if(!CombatRewards.excludesRewardTags(killed.entityTags()))grantCombatExperience("), "Combat XP is not gated on the dungeon tag");
        check(networking.contains("!CombatRewards.excludesRewardTags(killed.entityTags()) && killed.getType().getDescriptionId().equals(\"entity.minecraft.zombie\")) recordQuestZombieKill("), "Field zombie quest is not gated on the dungeon tag");

        // 2. Personal P3 reward isolation with members who left.
        FixtureDatabase.databaseName();
        FixtureMigrations.apply();
        check(count("SELECT count(*) FROM pg_indexes WHERE schemaname='dalmaru' AND indexname IN ('character_currency_ledger_non_mail_once','character_currency_ledger_mail_event_once')") == 2, "V039 ledger identity indexes are missing");
        var repo = new RunRepository();
        var service = new RunMemberService();
        var adapter = new RunMemberExit();
        var characters = new CharacterRepository();
        List<UUID> accounts = new ArrayList<>(), runs = new ArrayList<>();
        try {
            // 2a. Voluntary, kick and expiry leave one member; the survivor is rewarded alone and gets a direct item.
            Party party = party(accounts, 4, "ri_a");
            UUID run = repo.enter(party.account(0), party.generation(0), 10, RunDifficulty.FIRST_STEP, party.characters()).runId();
            runs.add(run);
            check(repo.activateParty(run), "Roster activation failed");
            UUID a = party.character(0), b = party.character(1), c = party.character(2), d = party.character(3);
            for (UUID member : party.characters()) repo.recordContribution(run, member, Action.EFFECT);
            Snapshot before = Snapshot.of(party.characters());
            check(service.exit(run, party.account(1), ExitReason.VOLUNTARY, RunMemberExit.ANY_REVISION).status() == ExitStatus.REMOVED, "Voluntary exit failed");
            long revision = number("SELECT roster_revision FROM dalmaru.run_party_control WHERE run_id=?", run);
            try (var connection = EnhancementReplayCheck.connection()) {
                connection.setAutoCommit(false);
                check(adapter.removeMember(connection, run, c, revision, ExitReason.KICKED).status() == ExitStatus.REMOVED, "Kick failed");
                connection.commit();
            }
            check(service.exit(run, party.account(3), ExitReason.RECONNECT_EXPIRED, RunMemberExit.ANY_REVISION).status() == ExitStatus.REMOVED, "Expiry exit failed");
            CharacterProfile survivorBefore = characters.profile(a);
            long survivorXp = totalExperience(survivorBefore);
            repo.finish(party.account(0), party.generation(0), 11, run, true, Map.of(a, Action.EFFECT, b, Action.EFFECT, c, Action.EFFECT, d, Action.EFFECT));
            check(repo.rewards(run).stream().map(RunReward::characterId).collect(Collectors.toSet()).equals(Set.of(a)), "Reward went to someone besides the survivor");
            check(number("SELECT count(*) FROM dalmaru.run_reward_grants WHERE run_id=?", run) == 1, "Grant rows are not exactly the survivor's");
            check(number("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE source_run_id=? AND reason='run.clear'", run) == 1 && number("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE source_run_id=? AND character_id='" + a + "'", run) == 1, "Ledger rows are not exactly the survivor's");
            check(totalExperience(characters.profile(a)) == survivorXp + 500, "Survivor XP wrong");
            for (UUID gone : List.of(b, c, d)) {
                check(number("SELECT count(*) FROM dalmaru.character_mailbox WHERE source_run_id=? AND character_id='" + gone + "'", run) == 0, "A member who left got mail");
                check(number("SELECT count(*) FROM dalmaru.run_auction_members m JOIN dalmaru.run_auctions r USING(auction_id) WHERE r.run_id=? AND m.character_id='" + gone + "'", run) == 0, "A member who left got an auction seat");
            }
            Snapshot after = Snapshot.of(party.characters());
            for (int i = 1; i < 4; i++) check(after.equals(before, i), "A member who left had wallet, materials, XP or clears changed: " + i);
            check(after.wallet[0] == before.wallet[0] + 70 && after.clears[0] == before.clears[0] + 1, "Survivor wallet or clear count wrong");
            check(single("SELECT state FROM dalmaru.run_auctions WHERE run_id=?", run).equals("DIRECT") && number("SELECT count(*) FROM dalmaru.run_auction_members m JOIN dalmaru.run_auctions r USING(auction_id) WHERE r.run_id=? AND m.character_id='" + a + "'", run) == 1, "Sole survivor did not get the direct auction item");
            long mails = number("SELECT count(*) FROM dalmaru.character_mailbox WHERE source_run_id=?", run);

            // Replays and late attempts change nothing, including by a member who left.
            repo.finish(party.account(0), party.generation(0), 11, run, true, Map.of(a, Action.EFFECT));
            repo.finish(party.account(0), party.generation(0), 12, run, true, Map.of(b, Action.EFFECT));
            reject(() -> repo.finish(party.account(1), party.generation(1), 13, run, true, Map.of(b, Action.EFFECT)));
            reject(() -> repo.recordContribution(run, b, Action.EFFECT));
            check(repo.rewards(run).size() == 1 && number("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE source_run_id=?", run) == 1 && number("SELECT count(*) FROM dalmaru.character_mailbox WHERE source_run_id=?", run) == mails, "Replay duplicated a reward, ledger row or mail");
            check(Snapshot.of(party.characters()).equals(after, 0) && Snapshot.of(party.characters()).equals(after, 1), "Replay changed balances");
            long wallet = number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", a);
            try { sql("INSERT INTO dalmaru.character_currency_ledger(event_id,character_id,source_run_id,reason,copper_delta) VALUES(?,?,?,'run.clear',70)", UUID.randomUUID(), a, run); throw new AssertionError("Duplicate run.clear ledger row accepted"); }
            catch (SQLException expected) { }
            check(number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?", a) == wallet, "Duplicate ledger attempt changed the wallet");

            // 2b. Two survivors share an open auction; the member who left has no seat and cannot revoke into it.
            Party second = party(accounts, 4, "ri_b");
            UUID run2 = repo.enter(second.account(0), second.generation(0), 20, RunDifficulty.FIRST_STEP, second.characters()).runId();
            runs.add(run2);
            repo.activateParty(run2);
            for (UUID member : second.characters()) repo.recordContribution(run2, member, Action.EFFECT);
            check(service.exit(run2, second.account(3), ExitReason.VOLUNTARY, RunMemberExit.ANY_REVISION).status() == ExitStatus.REMOVED, "Second voluntary exit failed");
            repo.finish(second.account(0), second.generation(0), 21, run2, true, Map.of(second.character(0), Action.EFFECT, second.character(1), Action.EFFECT, second.character(2), Action.EFFECT, second.character(3), Action.EFFECT));
            check(repo.rewards(run2).stream().map(RunReward::characterId).collect(Collectors.toSet()).equals(Set.of(second.character(0), second.character(1), second.character(2))), "Three survivors were not exactly the rewarded set");
            check(single("SELECT state FROM dalmaru.run_auctions WHERE run_id=?", run2).equals("OPEN"), "Shared auction did not open");
            check(number("SELECT count(*) FROM dalmaru.run_auction_members m JOIN dalmaru.run_auctions r USING(auction_id) WHERE r.run_id=? AND NOT m.revoked", run2) == 3 && number("SELECT count(*) FROM dalmaru.run_auction_members m JOIN dalmaru.run_auctions r USING(auction_id) WHERE r.run_id=? AND m.character_id='" + second.character(3) + "'", run2) == 0, "Auction seats are not exactly the survivors");
            UUID auction = UUID.fromString(single("SELECT auction_id::text FROM dalmaru.run_auctions WHERE run_id=?", run2));
            reject(() -> new AuctionRepository().revokeParticipant(auction, second.character(3)));
            check(number("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE source_run_id=? AND character_id='" + second.character(3) + "'", run2) == 0, "Member who left has a ledger row");
            System.out.println("Dungeon loot/XP/quest exclusion (mobs, bosses, linger), survivor-only P3 reward/ledger/mail/XP/weekly/auction, V039 identity, replay and late-attempt isolation PASS");
        } finally {
            for (UUID run : runs) sql("DELETE FROM dalmaru.run_instances WHERE run_id=?", run);
            for (UUID account : accounts) { sql("DELETE FROM dalmaru.operation_requests WHERE account_id=?", account); EnhancementReplayCheck.cleanup(account); }
        }
    }

    private record Party(List<UUID> accounts, List<Long> generations, List<UUID> characters) {
        UUID account(int index) { return accounts.get(index); }
        long generation(int index) { return generations.get(index); }
        UUID character(int index) { return characters.get(index); }
    }

    private static final class Snapshot {
        final long[] wallet, materials, xp, clears;
        private Snapshot(int size) { wallet = new long[size]; materials = new long[size]; xp = new long[size]; clears = new long[size]; }
        static Snapshot of(List<UUID> members) throws Exception {
            var value = new Snapshot(members.size());
            for (int i = 0; i < members.size(); i++) {
                UUID id = members.get(i);
                value.wallet[i] = number("SELECT COALESCE(SUM(copper),0) FROM dalmaru.character_wallet WHERE character_id=?", id);
                value.materials[i] = number("SELECT COALESCE(SUM(quantity),0) FROM dalmaru.character_materials WHERE character_id=?", id);
                value.xp[i] = totalExperience(new CharacterRepository().profile(id));
                value.clears[i] = number("SELECT COALESCE(SUM(clear_count),0) FROM dalmaru.character_run_weekly_clears WHERE character_id=?", id);
            }
            return value;
        }
        boolean equals(Snapshot other, int index) {
            return wallet[index] == other.wallet[index] && materials[index] == other.materials[index] && xp[index] == other.xp[index] && clears[index] == other.clears[index];
        }
    }

    private static Party party(List<UUID> accounts, int size, String prefix) throws Exception {
        List<UUID> own = new ArrayList<>(), characters = new ArrayList<>();
        List<Long> generations = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            UUID account = UUID.randomUUID();
            accounts.add(account);
            own.add(account);
            long generation = new AccountRepository().openSession(account).connectionGeneration();
            generations.add(generation);
            UUID character = new CharacterRepository().create(account, generation, 1, 1, prefix + "_" + account.toString().substring(0, 6)).character().characterId();
            new CharacterRepository().selectActive(account, generation, 2, character);
            sql("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?", character);
            characters.add(character);
        }
        return new Party(own, generations, characters);
    }

    private static long totalExperience(CharacterProfile profile) {
        long total = profile.experience();
        for (int level = 1; level < profile.level(); level++) total += CharacterRepository.experienceNeededForNextLevel(level);
        return total;
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError("Missing " + signature);
        int open = source.indexOf('{', start), depth = 1, end = open + 1;
        for (; depth > 0; end++) { char value = source.charAt(end); if (value == '{') depth++; else if (value == '}') depth--; }
        return source.substring(start, end);
    }

    private static int count(String text, String needle) {
        int total = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) total++;
        return total;
    }

    private static long count(String sql) throws SQLException { return number(sql); }

    private static String single(String text, Object... args) throws SQLException {
        try (var c = EnhancementReplayCheck.connection(); var q = c.prepareStatement(text)) {
            for (int i = 0; i < args.length; i++) q.setObject(i + 1, args[i]);
            try (var row = q.executeQuery()) { row.next(); return row.getString(1); }
        }
    }

    private static void sql(String text, Object... args) throws SQLException {
        try (var c = EnhancementReplayCheck.connection(); var q = c.prepareStatement(text)) { for (int i = 0; i < args.length; i++) q.setObject(i + 1, args[i]); q.executeUpdate(); }
    }

    private static long number(String text, Object... args) throws SQLException {
        try (var c = EnhancementReplayCheck.connection(); var q = c.prepareStatement(text)) {
            for (int i = 0; i < args.length; i++) q.setObject(i + 1, args[i]);
            try (var row = q.executeQuery()) { row.next(); return row.getLong(1); }
        }
    }

    @FunctionalInterface private interface Operation { void run() throws Exception; }

    private static void reject(Operation operation) {
        try { operation.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Operation was accepted");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
