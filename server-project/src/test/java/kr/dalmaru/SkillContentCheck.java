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
        var legacy=JsonParser.parseString(source).getAsJsonObject();legacy.addProperty("schemaVersion",1);legacy.addProperty("contentVersion","pilot-j02-1");legacy.getAsJsonArray("runs").get(0).getAsJsonObject().addProperty("version","pilot-j02-1");
        var refs=new JsonArray();for(var skill:current.skills()){var ref=new JsonObject();ref.addProperty("id",skill.id());ref.addProperty("enabled",true);refs.add(ref);}legacy.add("skills",refs);
        for(var skill:current.skills()){var old=ContentCatalog.parse(legacy.toString()).skill(skill.id());check(old.name().equals(skill.name())&&old.description().equals(skill.description())&&old.energy()==skill.energy()&&old.cooldownTicks()==skill.cooldownTicks(),"Schema1 changed historical metadata");}
        var changed=JsonParser.parseString(source).getAsJsonObject();var first=changed.getAsJsonArray("skills").get(0).getAsJsonObject();
        first.getAsJsonObject("cast").addProperty("energy",21);first.getAsJsonObject("display").addProperty("description","Changed display");
        check(ContentCatalog.parse(changed.toString()).skill("skill.warrior.sweep").energy()==21&&ContentCatalog.parse(changed.toString()).skill("skill.warrior.sweep").description().equals("Changed display")&&ContentCatalog.parse(legacy.toString()).skill("skill.warrior.sweep").energy()==20,"Definitions ignored data or legacy used current tuning");
        try{current.skill("skill.warrior.sweep").weaponProfiles().clear();throw new AssertionError("Mutable skill weapons");}catch(UnsupportedOperationException expected){}
        effects(source,current,legacy);
        reject(source,s->s.remove("display"));
        reject(source,s->s.getAsJsonObject("display").addProperty("name",""));
        reject(source,s->s.addProperty("profession","MAGE"));
        reject(source,s->s.addProperty("kind","PASSIVE"));
        reject(source,s->s.getAsJsonObject("cast").addProperty("energy",-1));
        reject(source,s->s.getAsJsonObject("cast").addProperty("cooldownTicks",0));
        reject(source,s->s.getAsJsonObject("unlock").addProperty("maxRank",6));
        reject(source,s->s.getAsJsonArray("weaponProfiles").add("fan"));
        reject(source,s->s.getAsJsonArray("weaponProfiles").remove(0));
        reject(source,s->s.getAsJsonArray("weaponProfiles").add("sword_shield"));
        reject(source,s->s.addProperty("contentVersion","bad/version"));
        var passive=JsonParser.parseString(source).getAsJsonObject();passive.getAsJsonArray("skills").get(4).getAsJsonObject().getAsJsonObject("cast").addProperty("energy",1);rejectCatalog(passive);
        legacy.addProperty("contentVersion","unknown");rejectCatalog(legacy);
        casts(current);
        System.out.println("24 skill metadata/effects/schema1-2 preservation, parameter rejection and16 actual cast boundaries PASS");
    }
    private static void effects(String source,ContentCatalog current,JsonObject legacy){
        for(var skill:current.skills())for(var entry:skill.parameters().entrySet()){
            var value=entry.getValue();check(value.at(0)==0&&Math.abs(value.at(1)-value.first())<1e-9&&Math.abs(value.at(5)-value.last())<1e-9&&Math.abs(value.at(3)-(value.first()+value.last())/2)<1e-9,"Rank interpolation changed");
        }
        check(current.skill("skill.rogue.smoke_bomb").parameter("durationTicks",5)==80&&current.skill("skill.rogue.shackle_strike").parameter("durationTicks",5)==60,"New duration correction lost");
        var old=ContentCatalog.parse(legacy.toString());check(old.skill("skill.rogue.smoke_bomb").parameter("durationTicks",5)==90&&old.skill("skill.rogue.shackle_strike").parameter("durationTicks",5)==80,"Schema1 used newer balance");
        var schema2=JsonParser.parseString(source).getAsJsonObject();schema2.addProperty("schemaVersion",2);schema2.addProperty("contentVersion","pilot-j02-2");
        schema2.getAsJsonArray("runs").get(0).getAsJsonObject().addProperty("version","pilot-j02-2");
        for(var entry:schema2.getAsJsonArray("skills")){var definition=entry.getAsJsonObject();definition.addProperty("schemaVersion",1);definition.addProperty("contentVersion","pilot-1");definition.remove("parameters");}
        check(ContentCatalog.parse(schema2.toString()).skills().equals(old.skills()),"Metadata-only schema2 changed old effects");
        reject(source,s->s.getAsJsonObject("parameters").remove("damageMultiplier"));
        reject(source,s->s.getAsJsonObject("parameters").add("unknown",new JsonArray()));
        reject(source,s->s.getAsJsonObject("parameters").getAsJsonArray("damageMultiplier").set(0,new JsonPrimitive(-1)));
        reject(source,s->s.getAsJsonObject("parameters").getAsJsonArray("damageMultiplier").set(1,new JsonPrimitive(0)));
        try{current.skill("skill.warrior.sweep").parameters().clear();throw new AssertionError("Mutable effects");}catch(UnsupportedOperationException expected){}
        var variant=JsonParser.parseString(source).getAsJsonObject();var mark=variant.getAsJsonArray("skills").get(8).getAsJsonObject().getAsJsonObject("parameters");
        mark.add("markBonus",JsonParser.parseString("[0.05,0.15]"));mark.add("markTicks",JsonParser.parseString("[40,40]"));
        var archived=ContentCatalog.parse(variant.toString());var effects=new kr.dalmaru.combat.CombatEffects();UUID caster=UUID.randomUUID(),target=UUID.randomUUID();
        effects.mark(caster,target,5,0,archived.skill("skill.archer.tracking_mark"));
        check(Math.abs(effects.markBonus(caster,target,39)-.15)<1e-9&&effects.markBonus(caster,target,40)==0&&effects.markBonus(UUID.randomUUID(),target,10)==0,"Mark lost fixed value/duration/ownership");
        effects.shield(target,"test",50,100,0,12);check(effects.shieldRemaining(target,11)==50&&effects.shieldRemaining(target,12)==0,"Shield ignored duration");
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
        check(count==16,"Active skill set incomplete");
        checks.append("var data=com.google.gson.JsonParser.parseString(kr.dalmaru.content.ContentCatalog.current().snapshot()).getAsJsonObject();data.getAsJsonArray(\"skills\").get(0).getAsJsonObject().getAsJsonObject(\"cast\").addProperty(\"energy\",21);var pinned=kr.dalmaru.content.ContentCatalog.parse(data.toString());runtime.contentSource=ignored->pinned;state.stamina=300;require(runtime.useSweep(id,1000000)==SweepResult.USED&&state.stamina==279,\"Cast ignored pinned provider\");");methods.append(method(source,"private SweepResult use("));methods.append("private java.util.function.Function<UUID,kr.dalmaru.content.ContentCatalog> contentSource=player->kr.dalmaru.content.ContentCatalog.current();");methods.append(method(source,"public kr.dalmaru.content.ContentCatalog content("));methods.append(method(source,"public kr.dalmaru.content.ContentCatalog.Skill skill("));
        var maps=new StringBuilder();var matches=java.util.regex.Pattern.compile("private final Map<UUID, Long> (\\w+)").matcher(source);
        while(matches.find())maps.append("Map<UUID,Long> ").append(matches.group(1)).append("=new HashMap<>();");
        String code="import java.util.*; public class CastProof {int successfulCasts;java.util.function.Consumer<UUID> skillUsed=ignored->successfulCasts++;enum SweepResult{UNAVAILABLE,STAMINA,COOLDOWN,USED}static class RuntimeState{double stamina;}Map<UUID,RuntimeState>states=new HashMap<>();static class Defense{void attack(){}}Defense defenseState(UUID id){return new Defense();}"+maps+methods+"static void require(boolean value,String msg){if(!value)throw new AssertionError(msg);}public static void run(){var runtime=new CastProof();var state=new RuntimeState();UUID id=UUID.randomUUID();runtime.states.put(id,state);"+checks+"}}";
        code=code.substring(0,code.length()-2)+"require(runtime.successfulCasts==33,\"Only successful casts emit mechanic event\");}}";
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
