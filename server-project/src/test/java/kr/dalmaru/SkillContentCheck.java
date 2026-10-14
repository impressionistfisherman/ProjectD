package kr.dalmaru;

import com.google.gson.*;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import java.util.function.Consumer;
import javax.tools.ToolProvider;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.skill.SkillDefinition;

public final class SkillContentCheck {
    public static void main(String[] args)throws Exception {
        String source=Files.readString(Path.of("src/main/resources/content/pilot.json"));
        var current=ContentCatalog.current();check(current.skills().size()==24,"Skill set incomplete");
        var legacy=JsonParser.parseString(source).getAsJsonObject();legacy.addProperty("schemaVersion",1);
        var refs=new JsonArray();for(var skill:current.skills()){var ref=new JsonObject();ref.addProperty("id",skill.id());ref.addProperty("enabled",true);refs.add(ref);}legacy.add("skills",refs);
        check(ContentCatalog.parse(legacy.toString()).skills().equals(current.skills()),"Schema1 changed historical skill definitions");
        var changed=JsonParser.parseString(source).getAsJsonObject();var first=changed.getAsJsonArray("skills").get(0).getAsJsonObject();
        first.getAsJsonObject("cast").addProperty("energy",21);first.getAsJsonObject("display").addProperty("description","Changed display");
        check(ContentCatalog.parse(changed.toString()).skill("skill.warrior.sweep").energy()==21&&ContentCatalog.parse(changed.toString()).skill("skill.warrior.sweep").description().equals("Changed display")&&ContentCatalog.parse(legacy.toString()).skill("skill.warrior.sweep").energy()==20,"Definitions ignored data or legacy used current tuning");
        try{current.skill("skill.warrior.sweep").weaponProfiles().clear();throw new AssertionError("Mutable skill weapons");}catch(UnsupportedOperationException expected){}
        reject(source,s->s.remove("display"));
        reject(source,s->s.getAsJsonObject("display").addProperty("name",""));
        reject(source,s->s.addProperty("profession","MAGE"));
        reject(source,s->s.addProperty("kind","PASSIVE"));
        reject(source,s->s.getAsJsonObject("cast").addProperty("energy",-1));
        reject(source,s->s.getAsJsonObject("cast").addProperty("cooldownTicks",0));
        reject(source,s->s.getAsJsonObject("unlock").addProperty("maxRank",6));
        reject(source,s->s.getAsJsonArray("weaponProfiles").add("fan"));
        reject(source,s->s.getAsJsonArray("weaponProfiles").add("sword_shield"));
        reject(source,s->s.addProperty("contentVersion","bad/version"));
        var passive=JsonParser.parseString(source).getAsJsonObject();passive.getAsJsonArray("skills").get(4).getAsJsonObject().getAsJsonObject("cast").addProperty("energy",1);rejectCatalog(passive);
        legacy.addProperty("contentVersion","unknown");rejectCatalog(legacy);
        casts(current);
        System.out.println("24 skill metadata/schema1 preservation,12 invalid inputs and16 actual cast boundaries PASS");
    }
    private static void casts(ContentCatalog content)throws Exception {
        String source=Files.readString(Path.of("src/main/java/kr/dalmaru/combat/CombatRuntime.java"));
        var methods=new StringBuilder();var checks=new StringBuilder();
        int[] costs={20,20,30,25,22,18,20,25,20,30,35,25,22,25,20,20};
        int[] cooldowns={90,140,180,360,100,140,240,280,80,240,280,240,100,320,140,240};int count=0;
        for(var skill:content.skills())if(skill.kind()==SkillDefinition.Kind.ACTIVE){
            check(skill.energy()==costs[count]&&skill.cooldownTicks()==cooldowns[count],"Legacy cast values changed: "+skill.id());count++;
            String suffix=skill.id().substring(skill.id().lastIndexOf('.')+1);String name="use"+Arrays.stream(suffix.split("_")).map(part->Character.toUpperCase(part.charAt(0))+part.substring(1)).reduce("",String::concat);
            methods.append(method(source,"public SweepResult "+name+"("));
            checks.append("state.stamina=300; require(runtime.").append(name).append("(id,1000)==SweepResult.USED&&state.stamina==").append(300-skill.energy()).append(",\"Cost\"); require(runtime.").append(name).append("(id,1001)==SweepResult.COOLDOWN&&state.stamina==").append(300-skill.energy()).append(",\"Duplicate charge\");state.stamina=").append(skill.energy()-1).append(";require(runtime.").append(name).append("(id,").append(1000+skill.cooldownTicks()).append(")==SweepResult.STAMINA,\"Low stamina\");state.stamina=300;require(runtime.").append(name).append("(id,").append(1000+skill.cooldownTicks()).append(")==SweepResult.USED,\"Cooldown boundary\");require(runtime.").append(name).append("(UUID.randomUUID(),1000)==SweepResult.UNAVAILABLE,\"Missing owner\");");
        }
        check(count==16,"Active skill set incomplete");methods.append(method(source,"private SweepResult use("));
        var maps=new StringBuilder();var matches=java.util.regex.Pattern.compile("private final Map<UUID, Long> (\\w+)").matcher(source);
        while(matches.find())maps.append("Map<UUID,Long> ").append(matches.group(1)).append("=new HashMap<>();");
        String code="import java.util.*; public class CastProof {enum SweepResult{UNAVAILABLE,STAMINA,COOLDOWN,USED}static class RuntimeState{double stamina;}Map<UUID,RuntimeState>states=new HashMap<>();static class Defense{void attack(){}}Defense defenseState(UUID id){return new Defense();}"+maps+methods+"static void require(boolean value,String msg){if(!value)throw new AssertionError(msg);}public static void run(){var runtime=new CastProof();var state=new RuntimeState();UUID id=UUID.randomUUID();runtime.states.put(id,state);"+checks+"}}";
        Path directory=Files.createTempDirectory("dalmaru-skill-content-");
        try {
            Path file=directory.resolve("CastProof.java");Files.writeString(file,code);
            check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",directory.toString(),file.toString())==0,"Cast fixture compilation");
            try(var loader=new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},SkillContentCheck.class.getClassLoader())){loader.loadClass("CastProof").getMethod("run").invoke(null);}
        } finally {try(var paths=Files.walk(directory)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
    private static String method(String source,String signature){int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,end=open+1;check(start>=0&&open>=0,"Missing actual method");while(depth>0){char c=source.charAt(end++);if(c=='{')depth++;if(c=='}')depth--;}return source.substring(start,end);}
    private static void reject(String source,Consumer<JsonObject> change){var root=JsonParser.parseString(source).getAsJsonObject();change.accept(root.getAsJsonArray("skills").get(0).getAsJsonObject());rejectCatalog(root);}
    private static void rejectCatalog(JsonObject root){try{ContentCatalog.parse(root.toString());}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid skill definition accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
