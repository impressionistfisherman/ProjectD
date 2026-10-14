package kr.dalmaru;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;

/** Executes the production UI methods and boss tick with lightweight platform doubles. */
public final class ManualPlayRegressionCheck {
    public static void main(String[] args) throws Exception {
        Path sourceRoot = args.length == 0 ? Path.of("src") : Path.of(args[0]);
        String state = Files.readString(sourceRoot.resolve("client/java/kr/dalmaru/client/ClientRunState.java"))
            .replace("package kr.dalmaru.client;", "").replace("import kr.dalmaru.network.DalmaruPayloads;", "");
        String profile = Files.readString(sourceRoot.resolve("client/java/kr/dalmaru/client/DalmaruProfileScreen.java"));
        String layout = profile.substring(profile.indexOf("\t@Override"), profile.indexOf("\t@Override", profile.indexOf("\t@Override") + 1));
        String runtime = Files.readString(sourceRoot.resolve("main/java/kr/dalmaru/run/RunRuntime.java"));
        String tick = runtime.substring(runtime.indexOf("    private void tickBoss("), runtime.indexOf("    private void damage("));
        String networking = Files.readString(sourceRoot.resolve("main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        String status = networking.substring(networking.indexOf("\tprivate static String runEntryStatus("), networking.indexOf("\tprivate static java.util.List<UUID> partyCharacterIds("));
        Path fixture = Files.createTempDirectory("dalmaru-manual-check-");
        try {
            Path source = fixture.resolve("ManualHarness.java");
            Files.writeString(source, HARNESS.replace("/* STATE */", state).replace("/* LAYOUT */", layout).replace("/* TICK */", tick).replace("/* STATUS */", status));
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", fixture.toString(), source.toString()) != 0)
                throw new IllegalStateException("Manual-play harness compilation failed.");
            try (var loader = new URLClassLoader(new java.net.URL[]{fixture.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                loader.loadClass("ManualHarness").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            }
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static final String HARNESS = """
        import java.util.*;
        /* STATE */
        class DalmaruPayloads { record RunResponse(long requestId, boolean accepted, String status, String state, UUID runId) { RunResponse(long id,boolean accepted,String status){this(id,accepted,status,"ACTIVE",UUID.randomUUID());} } }
        class Component { static String literal(String text) { return text; } }
        class Button {
            boolean active = true; int x,y,w,h;
            interface OnPress { void press(Button button); }
            static Button builder(String text, OnPress action) { return new Button(); }
            Button bounds(int x,int y,int w,int h) { this.x=x;this.y=y;this.w=w;this.h=h;return this; }
            Button build() { return this; }
        }
        class DalmaruClient { static void requestCharacterStatReset() {} static void requestCharacterStatInvestment(int id) {} }
        class DalmaruProfessionScreen { DalmaruProfessionScreen(Object parent) {} }
        class DalmaruSkillScreen { DalmaruSkillScreen(Object parent) {} }
        class Screen {
            int width,height; final List<Button> buttons = new ArrayList<>(); final Screen minecraft = this;
            protected void init() {} void setScreen(Object screen) {} Button addRenderableWidget(Button button) { buttons.add(button); return button; }
        }
        class Profile extends Screen {
            Button statusButton,strengthButton,dexterityButton,arcanaButton,vitalityButton,spiritButton,resetButton,professionButton,skillButton;
            /* LAYOUT */
        }
        public class ManualHarness {
            static class Logger { void warn(String message,Throwable cause) {} }
            static final Logger LOGGER = new Logger();
            /* STATUS */
            enum Stage { GATEKEEPER, BEACON }
            static class MinecraftServer {}
            static class ParticleTypes { static final Object CRIT=new Object(),SOUL_FIRE_FLAME=new Object(),SOUL=new Object(); }
            static class Mob { double x,z; boolean isAlive(){return true;} float getHealth(){return 100;} float getMaxHealth(){return 100;} double getX(){return x;} double getZ(){return z;} }
            static class ServerPlayer {
                double x,z,horizontal;
                double distanceToSqr(Mob boss){return (x-boss.x)*(x-boss.x)+(z-boss.z)*(z-boss.z);}
                double getX(){return x;} double getY(){return 101;} double getZ(){return z;}
                void push(double x,double y,double z){horizontal=Math.hypot(x,z);}
                void knockback(double strength,double x,double z){horizontal=strength;}
            }
            static class Level {
                final Mob boss = new Mob(); Object getEntity(UUID id){return boss;}
                void sendParticles(Object particle,double x,double y,double z,int count,double a,double b,double c,double speed){}
            }
            static class Encounter { UUID runId=UUID.randomUUID(),bossId=UUID.randomUUID(); Level level=new Level(); Stage stage=Stage.GATEKEEPER; int bossTicks=59; }
            interface BossDamage { void hit(ServerPlayer player,Mob boss,float amount,boolean magic); }
            final BossDamage bossDamage=(player,boss,amount,magic)->{};
            final ServerPlayer target = new ServerPlayer();
            List<ServerPlayer> players(MinecraftServer server,UUID runId,Level level){return List.of(target);}
            float attack(Encounter encounter,int amount){return amount;}
            void damage(List<ServerPlayer> players,Mob boss,double range,float amount,boolean magic,Object particle){}
            /* TICK */
            public static void main(String[] args) {
                for(int[] size:new int[][]{{320,240},{382,259},{640,360}}) {
                    Profile screen=new Profile();screen.width=size[0];screen.height=size[1];screen.init();
                    require(screen.buttons.size()==9,"Missing profile action");
                    for(Button button:screen.buttons) require(button.x>=0 && button.y>=0 && button.x+button.w<=screen.width && button.y+button.h<=screen.height,"Profile button clipped");
                }
                ClientRunState.resetForConnection(); long request=ClientRunState.nextRequestId();
                require(request>0 && ClientRunState.nextRequestId()==0,"Duplicate entry sent while waiting");
                require(!ClientRunState.accept(new DalmaruPayloads.RunResponse(request+1,true,"stale")) && !ClientRunState.canRequest(),"Unmatched response changed pending request");
                require(ClientRunState.accept(new DalmaruPayloads.RunResponse(request,true,"entered")) && ClientRunState.canRequest(),"Accepted entry did not finish waiting");
                UUID preparation=UUID.randomUUID();require(ClientRunState.prepare(new DalmaruPayloads.RunResponse(99,true,"ready","READY",preparation)),"READY ignored");ClientRunState.accept(new DalmaruPayloads.RunResponse(99,false,"cancelled","CANCELLED",preparation));require(!ClientRunState.preparing(),"Cancellation kept READY UI");
                long second=ClientRunState.nextRequestId(); require(second>request,"Request ID reused");
                require(!ClientRunState.accept(new DalmaruPayloads.RunResponse(second,false,"denied")) && ClientRunState.canRequest(),"Denial did not allow retry");
                require(runEntryStatus(new java.util.concurrent.CompletionException(new IllegalArgumentException("Character already has an active run."))).contains("진행 중"),"Active run denial hidden");
                require(runEntryStatus(new IllegalArgumentException("Level requirement not met.")).contains("20레벨"),"Level denial hidden");
                require(runEntryStatus(new IllegalArgumentException("Pending run return requires recovery.")).contains("재접속"),"Pending return recovery instruction hidden");
                require(!runEntryStatus(new java.sql.SQLException("private detail")).contains("private detail"),"Internal failure exposed");
                var harness=new ManualHarness();var encounter=new Encounter();harness.target.x=4;harness.tickBoss(new MinecraftServer(),encounter);
                require(harness.target.horizontal>0 && harness.target.horizontal<=.4,"Shield push grows with boss distance");
                System.out.println("Profile bounds, duplicate/stale entry responses, safe denial reasons and bounded boss knockback PASS (production methods, platform doubles).");
            }
            static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
        }
        """;
}
