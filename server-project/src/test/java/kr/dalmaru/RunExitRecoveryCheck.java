package kr.dalmaru;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** Execute the production recovery body, and verify all return routes call it after teleport. */
public final class RunExitRecoveryCheck {
    public static void main(String[] args) throws Exception {
        String runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        String network = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        String recovery = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunExitRecovery.java"));
        check(method(network,"private static void completeRun(").contains("RUN_RUNTIME.close(server,runId)"), "Completion bypasses shared close");
        check(method(network,"private static void failRunAfterFinalDeath(").contains("RUN_RUNTIME.close(server,death.runId())"), "Failure bypasses shared close");
        afterTeleport(method(runtime,"public void close(MinecraftServer server,UUID runId)"));
        String exit = method(runtime,"private void publishExit(");
        afterTeleport(exit);
        check(exit.indexOf("RunExitRecovery.restore(player)") < exit.indexOf("returned=true"), "Exit acknowledged before recovery");
        String pending = method(network,"private static void restorePendingReturn(");
        afterTeleport(pending);
        check(pending.indexOf("RunExitRecovery.restore(player)") < pending.indexOf("saveAll()"), "Reconnect return saved before recovery");
        Path dir = Files.createTempDirectory("run-exit-recovery-");
        try {
            Path file = dir.resolve("ExitRecoveryHarness.java");
            Files.writeString(file, HARNESS.replace("/*RECOVERY*/", method(recovery,"public static void restore(")));
            int result = ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",dir.toString(),file.toString(),"src/main/java/kr/dalmaru/combat/CombatUnits.java");
            check(result == 0, "Recovery harness compile failed");
            try (var loader = new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                loader.loadClass("ExitRecoveryHarness").getMethod("run").invoke(null);
            }
        } finally {
            try (var files = Files.walk(dir)) { for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
        System.out.println("PASS completion/failure/member-exit full HP+food, health ratio, reconnect return wiring");
    }
    private static void afterTeleport(String source) {
        int teleport = source.indexOf("player.teleportTo("), heal = source.indexOf("RunExitRecovery.restore(player)");
        check(teleport >= 0 && heal > teleport, "Return path does not recover after successful teleport");
    }
    private static String method(String source,String signature) {
        int start=source.indexOf(signature); check(start>=0,"Missing method: "+signature);
        int open=source.indexOf('{',start), depth=1, end=open+1;
        for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}
        return source.substring(start,end);
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static final String HARNESS = """
        import kr.dalmaru.combat.CombatUnits;
        public class ExitRecoveryHarness {
            static class Food {
                int level=3; float saturation=0;
                void setFoodLevel(int value){level=value;}
                void setSaturation(float value){saturation=value;}
            }
            static class ServerPlayer {
                float health,maximum; Food food=new Food();
                ServerPlayer(float max,float hp){maximum=max;health=hp;}
                float getMaxHealth(){return maximum;}
                void setHealth(float value){health=value;}
                Food getFoodData(){return food;}
            }
            /*RECOVERY*/
            public static void run(){
                String[] reasons={"completion","failure","member-exit"};
                float[] maximum={45.5f,1024f,20f}; double[] rpgMaximum={910,46800,400};
                for(int i=0;i<reasons.length;i++){
                    ServerPlayer p=new ServerPlayer(maximum[i],i==1?0:1);
                    restore(p);
                    check(p.health==p.maximum,reasons[i]+" engine health not full");
                    check(CombatUnits.hudRpg(rpgMaximum[i],p.health,p.maximum)==rpgMaximum[i],reasons[i]+" RPG ratio not full");
                    check(p.food.level==20&&p.food.saturation==20,reasons[i]+" hunger not full");
                    restore(p);check(p.health==p.maximum,reasons[i]+" retry over-healed");
                    p.maximum+=1;restore(p);check(p.health==p.maximum,reasons[i]+" used stale maximum");
                }
            }
            static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
        }
        """;
}
