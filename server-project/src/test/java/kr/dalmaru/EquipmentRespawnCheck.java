package kr.dalmaru;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;

/** Runs the production asynchronous callback with a delayed repository and replaceable player registry. */
public final class EquipmentRespawnCheck {
    public static void main(String[] args) throws Exception {
        Path source = args.length == 0 ? Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java") : Path.of(args[0]);
        String networking = Files.readString(source);
        String method = networking.substring(networking.indexOf("\tprivate static void refreshCombat("), networking.indexOf("\tprivate static void partyInvite("));
        Path fixture = Files.createTempDirectory("dalmaru-respawn-check-");
        try {
            Path harness = fixture.resolve("RespawnHarness.java");
            Files.writeString(harness, HARNESS.replace("/* PRODUCTION_METHOD */", method));
            int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", fixture.toString(), harness.toString());
            if (compiled != 0) throw new IllegalStateException("Respawn harness compilation failed.");
            try (var loader = new URLClassLoader(new java.net.URL[]{fixture.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                loader.loadClass("RespawnHarness").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            }
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static final String HARNESS = """
        import java.util.*;
        import java.util.concurrent.*;
        public class RespawnHarness {
            static final Map<UUID, ConnectionState> CONNECTIONS = new HashMap<>();
            static final Repository EQUIPMENT = new Repository();
            static final Combat COMBAT = new Combat();
            static final Logger LOGGER = new Logger();
            static final MinecraftServer SERVER = new MinecraftServer();
            record CharacterProfile(UUID characterId) { }
            record AccountSession(UUID activeCharacterId) { }
            static class ConnectionState {
                AccountSession accountSession;
                CharacterProfile activeProfile;
                long equipmentRevision;
            }
            static class ServerPlayer {
                final UUID id;
                double maximumHealth = 680, health = 680;
                ServerPlayer(UUID id) { this.id = id; }
                UUID getUUID() { return id; }
                void sendSystemMessage(String message) { throw new AssertionError(message); }
            }
            static class MinecraftServer {
                ServerPlayer current;
                final BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
                MinecraftServer getPlayerList() { return this; }
                ServerPlayer getPlayer(UUID id) { return current != null && current.id.equals(id) ? current : null; }
                void execute(Runnable task) { callbacks.add(task); }
            }
            static class Repository {
                final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
                List<String> equipped(UUID id) throws InterruptedException {
                    started.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Repository response timed out.");
                    return List.of("equipped");
                }
            }
            static class Combat {
                ServerPlayer appliedTo;
                void clearEquipment(ServerPlayer player, CharacterProfile profile) { }
                void equipment(ServerPlayer player, CharacterProfile profile, List<String> records) {
                    double ratio = player.health / player.maximumHealth;
                    player.maximumHealth = 740;
                    player.health = ratio * player.maximumHealth;
                    appliedTo = player;
                }
            }
            static class Logger { void warn(String text, Object id, Throwable error) { throw new AssertionError(text, error); } }
            static class Component { static String literal(String text) { return text; } }
            static MinecraftServer serverFor(ServerPlayer player) { return SERVER; }
            /* PRODUCTION_METHOD */
            public static void main(String[] args) throws Exception {
                UUID id = UUID.randomUUID(), character = UUID.randomUUID();
                ServerPlayer oldPlayer = new ServerPlayer(id);
                SERVER.current = oldPlayer;
                ConnectionState state = new ConnectionState();
                state.activeProfile = new CharacterProfile(character);
                state.accountSession = new AccountSession(character);
                CONNECTIONS.put(id, state);
                try {
                    refreshCombat(oldPlayer, state.activeProfile);
                    if (!EQUIPMENT.started.await(5, TimeUnit.SECONDS)) throw new AssertionError("Lookup did not start.");
                    ServerPlayer respawned = new ServerPlayer(id);
                    respawned.health = respawned.maximumHealth * .35;
                    SERVER.current = respawned;
                    EQUIPMENT.release.countDown();
                    Runnable response = SERVER.callbacks.poll(5, TimeUnit.SECONDS);
                    if (response == null) throw new AssertionError("Response was not scheduled.");
                    response.run();
                    if (COMBAT.appliedTo != respawned || oldPlayer.maximumHealth != 680)
                        throw new AssertionError("Equipment response must update the current respawned entity only.");
                    if (respawned.maximumHealth != 740 || Math.abs(respawned.health - 259) > .001)
                        throw new AssertionError("Respawn health ratio must remain 35% after the equipment response.");
                    System.out.println("Delayed equipment response across respawn PASS (production callback, stubbed server/combat).");
                } finally { EQUIPMENT.release.countDown(); }
            }
        }
        """;
}
