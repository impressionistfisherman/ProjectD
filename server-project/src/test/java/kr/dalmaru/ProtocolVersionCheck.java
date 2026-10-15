package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import kr.dalmaru.network.DalmaruNetworking;
import kr.dalmaru.network.DalmaruPayloads;

/** Protocol 32 gate: constant, handshake codecs, and the mismatch-reject path on both sides. */
public final class ProtocolVersionCheck {
    public static void main(String[] args) throws Exception {
        require(DalmaruNetworking.PROTOCOL_VERSION == 32, "PROTOCOL_VERSION is not 32");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            for (int version : new int[]{31, 32}) {
                DalmaruPayloads.Hello.CODEC.encode(buffer, new DalmaruPayloads.Hello(version));
                require(DalmaruPayloads.Hello.CODEC.decode(buffer).protocolVersion() == version, "Hello roundtrip changed");
                DalmaruPayloads.Welcome.CODEC.encode(buffer, new DalmaruPayloads.Welcome(version));
                require(DalmaruPayloads.Welcome.CODEC.decode(buffer).protocolVersion() == version, "Welcome roundtrip changed");
                buffer.clear();
            }
        } finally { buffer.release(); }

        // Server: a mismatched Hello is disconnected before the connection is marked compatible or Welcome is sent.
        String server = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        int hello = server.indexOf("registerGlobalReceiver(DalmaruPayloads.Hello.TYPE");
        require(hello >= 0, "Hello receiver missing");
        int mismatch = server.indexOf("payload.protocolVersion() != PROTOCOL_VERSION", hello);
        int disconnect = server.indexOf("disconnect(context, \"달마루 통신 버전이 맞지 않습니다.\");", mismatch);
        int compatible = server.indexOf("state.compatible = true;", hello);
        int welcome = server.indexOf("new DalmaruPayloads.Welcome(PROTOCOL_VERSION)", hello);
        require(mismatch > hello && disconnect > mismatch && compatible > disconnect && welcome > compatible,
            "Server mismatch must disconnect before compatible/Welcome");
        require(server.substring(disconnect, compatible).contains("return;"), "Server mismatch branch does not return");

        // Client: sends the shared constant and trusts only an equal Welcome.
        String client = Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruClient.java"));
        require(client.contains("new DalmaruPayloads.Hello(DalmaruNetworking.PROTOCOL_VERSION)"), "Client Hello does not use PROTOCOL_VERSION");
        String state = Files.readString(Path.of("src/client/java/kr/dalmaru/client/ClientConnectionState.java"));
        int accept = state.indexOf("acceptWelcome(int protocolVersion)");
        int equal = state.indexOf("protocolVersion == DalmaruNetworking.PROTOCOL_VERSION", accept);
        int set = state.indexOf("compatible = true;", accept);
        require(accept >= 0 && equal > accept && set > equal, "Client accepts Welcome without version equality");
        System.out.println("Protocol 32 constant/handshake/mismatch-reject PASS");
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
