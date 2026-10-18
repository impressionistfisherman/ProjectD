package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import kr.dalmaru.network.DalmaruNetworking;
import kr.dalmaru.network.DalmaruPayloads;
import kr.dalmaru.party.P4Contract;
import kr.dalmaru.party.PartyReturnAdapter;
import kr.dalmaru.party.PartyRules;

/** Protocol 34 gate: constant, handshake codecs, and an explicit reject of a P3 client. */
public final class ProtocolVersionCheck {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        require(DalmaruNetworking.PROTOCOL_VERSION == 34, "PROTOCOL_VERSION is not 34");
        require(!DalmaruNetworking.acceptsProtocol(33), "P3 protocol 33 must be rejected");
        require(DalmaruNetworking.acceptsProtocol(34), "Protocol 34 must be accepted");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            for (int version : new int[]{32, 33, 34}) {
                DalmaruPayloads.Hello.CODEC.encode(buffer, new DalmaruPayloads.Hello(version));
                require(DalmaruPayloads.Hello.CODEC.decode(buffer).protocolVersion() == version, "Hello roundtrip changed");
                DalmaruPayloads.Welcome.CODEC.encode(buffer, new DalmaruPayloads.Welcome(version));
                require(DalmaruPayloads.Welcome.CODEC.decode(buffer).protocolVersion() == version, "Welcome roundtrip changed");
                buffer.clear();
            }
            var play = new DalmaruPayloads.OperationRequest(1, java.util.UUID.randomUUID(), 1, java.util.UUID.randomUUID(), "vault_move", "{\"assetId\":\"copper\",\"quantity\":1,\"direction\":\"deposit\"}");
            DalmaruPayloads.OperationRequest.CODEC.encode(buffer, play);
            require(play.equals(DalmaruPayloads.OperationRequest.CODEC.decode(buffer)), "Play access envelope roundtrip changed");
            buffer.clear();
            var request = new DalmaruPayloads.P4Request(7, "party_menu", "{}");
            DalmaruPayloads.P4Request.CODEC.encode(buffer, request);
            require(request.equals(DalmaruPayloads.P4Request.CODEC.decode(buffer)), "P4 request roundtrip changed");
            buffer.clear();
            var statePayload = new DalmaruPayloads.P4State(7, "REJECT", 0, 0, "거절: 파티 명단은 아직 열리지 않았습니다.");
            DalmaruPayloads.P4State.CODEC.encode(buffer, statePayload);
            require(statePayload.equals(DalmaruPayloads.P4State.CODEC.decode(buffer)), "P4 state roundtrip changed");
            buffer.clear();
            var hudPayload = new DalmaruPayloads.P4BrazierHud(3, 1, 2, 40, "STARTED", "WARNING", 200);
            DalmaruPayloads.P4BrazierHud.CODEC.encode(buffer, hudPayload);
            require(hudPayload.equals(DalmaruPayloads.P4BrazierHud.CODEC.decode(buffer)), "Brazier HUD roundtrip changed");
            buffer.clear();
        } finally { buffer.release(); }
        for (String action : new String[]{"party_menu", "skill_used", "hit", "boss_snapshot", "participant_removed", "return_ack", "s12_cancel", "invite"}) {
            var judged = P4Contract.judge(action);
            require("REJECT".equals(judged.status()) && judged.reason().startsWith("거절"), "P4 action was treated as success: " + action);
        }
        require(P4Contract.judge("party_menu").reason().contains("파티 명단"), "Party menu reject reason missing");
        var adapter = PartyReturnAdapter.cancelReward(null, null, null);
        require("REJECT".equals(adapter.status()) && adapter.reason().startsWith("거절"), "Return adapter reported success");
        var rules = PartyRules.current();
        require(rules.temporary() && rules.protocol() == 34 && rules.maxMembers() == 4 && rules.recruitOnlyWhileReady() && rules.voteMinPresent() == 3 && rules.voteExpireSeconds() == 30 && rules.revoteWaitSeconds() == 60 && rules.reconnectSeconds() == 180, "Party rules drifted");

        // Server: a mismatched Hello is disconnected before the connection is marked compatible or Welcome is sent.
        String server = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        int hello = server.indexOf("registerGlobalReceiver(DalmaruPayloads.Hello.TYPE");
        require(hello >= 0, "Hello receiver missing");
        int mismatch = server.indexOf("!acceptsProtocol(payload.protocolVersion())", hello);
        int disconnect = server.indexOf("disconnect(context, \"달마루 통신 버전이 맞지 않습니다.\");", mismatch);
        int compatible = server.indexOf("state.compatible = true;", hello);
        int welcome = server.indexOf("new DalmaruPayloads.Welcome(PROTOCOL_VERSION)", hello);
        require(mismatch > hello && disconnect > mismatch && compatible > disconnect && welcome > compatible,
            "Server mismatch must disconnect before compatible/Welcome");
        require(server.substring(disconnect, compatible).contains("return;"), "Server mismatch branch does not return");
        require(server.contains("COMBAT.mechanicActions(") && server.contains("RUN_RUNTIME.skillUsed(player)") && server.contains("RUN_RUNTIME::receivedHit"), "Skill and hit channel hooks missing");

        // Client: sends the shared constant and trusts only an equal Welcome.
        String client = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruClient.java"));
        require(client.contains("new DalmaruPayloads.Hello(DalmaruNetworking.PROTOCOL_VERSION)"), "Client Hello does not use PROTOCOL_VERSION");
        require(server.contains("\"delete_character\"") && server.contains("\"restore_character\"") && server.contains("\"list_villages\"") && server.contains("\"open_village\"") && server.contains("\"quest_stage\""), "P2 packet operations missing");
        require(server.contains("\"vault_move\"") && server.contains("\"shop_trade\"") && server.contains("\"gather_node\"") && server.contains("\"craft_recipe\"") && server.contains("\"direct_trade\"") && server.contains("\"claim_mail\"") && server.contains("\"view_auction\"") && server.contains("\"bid_auction\"") && server.contains("\"leave_auction\""), "P3 play operations missing");
        int play = server.indexOf("private static void playAccess");
        int position = server.indexOf("player.blockPosition()", play);
        int async = server.indexOf("supplyAsync", play);
        require(play > 0 && position > play && async > position, "Play access must read the server position before the async call");
        String access = Files.readString(Path.of("src/main/java/kr/dalmaru/play/PlayAccess.java"));
        require(access.contains("forRun") && access.contains("bidJournaled") && access.contains("leaveJournaled") && access.contains("pending"), "Play access does not call the trade and auction reads");
        require(!access.contains("text(body, \"x\")") && !access.contains("text(body, \"dimension\")"), "Play access reads client coordinates");
        String screen = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruPlayScreen.java"));
        require(screen.contains("임시 거래 받기") && screen.contains("임시 경매 입찰") && screen.contains("minimumBid()"), "Play screen does not send the server minimum bid");
        require(client.contains("requestCharacterLife") && client.contains("requestVillage") && client.contains("requestPlay") && Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruProfileScreen.java")).contains("전"), "P2 client screens missing");
        require(Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruPlayScreen.java")).contains("임시"), "Play screen is missing the temporary label");
        String state = Files.readString(Path.of("src/client/java/kr/dalmaru/client/ClientConnectionState.java"));
        int accept = state.indexOf("acceptWelcome(int protocolVersion)");
        int equal = state.indexOf("acceptsProtocol(protocolVersion)", accept);
        int set = state.indexOf("compatible = true;", accept);
        require(accept >= 0 && equal > accept && set > equal, "Client accepts Welcome without version equality");
        String run = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruRunScreen.java"));
        String party = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruPartyScreen.java"));
        require(run.contains("임시 파티") && run.contains("openParty()") && run.contains("ClientRunState.status()"), "F6 party entry missing");
        require(party.contains("임시 파티") && party.contains("로딩") && party.contains("requestParty(\"party_menu\")"), "Party frame does not show loading");
        require(!party.contains("성공"), "Party frame shows success");
        int use = server.indexOf("UseBlockCallback.EVENT.register");
        int brazier = server.indexOf("startBrazier", use);
        int protect = server.indexOf("protectedBlock", brazier);
        require(use > 0 && brazier > use && protect > brazier && server.contains("cancelBrazier"), "Brazier click must reach the server before the protected-block reject");
        String hud = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruBrazierHud.java"));
        String brazierState = Files.readString(Path.of("src/client/java/kr/dalmaru/client/ClientBrazierState.java"));
        require(hud.contains("ClientBrazierState.line()") && hud.contains("neglectLine()") && brazierState.contains("임시 화로") && brazierState.contains("남은 "), "Brazier HUD missing progress");
        require(brazierState.contains("임시 방치") && brazierState.contains("임시 경고") && brazierState.contains("임시 피해 중"), "Neglect HUD missing");
        require(client.contains("cancel_brazier") && brazierState.contains("채널 진행") && !hud.contains("bosses") && !brazierState.contains("boss") && !server.contains("snapshot.bosses") && !hud.contains("성공") && !brazierState.contains("성공"), "Brazier HUD shows bosses or success");
        require(client.contains("SoundEvents.BELL_BLOCK") && client.contains("SoundEvents.PLAYER_HURT") && client.contains("!\"WARNING\".equals(previous)") && client.contains("!\"DAMAGING\".equals(previous)") && !hud.contains("playSound"), "Neglect sound is not a one-shot vanilla transition");
        System.out.println("Protocol 34 constant/handshake/P3-mismatch-reject PASS");
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
