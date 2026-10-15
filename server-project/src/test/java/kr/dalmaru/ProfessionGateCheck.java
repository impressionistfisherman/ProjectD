package kr.dalmaru;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
import com.google.gson.JsonObject;
import kr.dalmaru.account.*;
import kr.dalmaru.request.RequestEnvelope;

public final class ProfessionGateCheck {
    public static void main(String[] args)throws Exception {
        if(!"grok_p3_fixture".equals(FixtureDatabase.databaseName()))throw new IllegalStateException("P3 gate fixture required");
        display();
        UUID account=UUID.randomUUID();String trap="profession_gate_"+account.toString().replace("-","");
        try {
            var characters=new CharacterRepository();long generation=new AccountRepository().openSession(account).connectionGeneration();
            UUID character=characters.create(account,generation,1,1,"gate_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            var flags=CharacterRepository.professionGateFlags(CharacterProfession.WARRIOR);
            complete(character,CharacterProfession.WARRIOR);sql("UPDATE dalmaru.character_profiles SET level=9 WHERE character_id=?",character);
            reject(()->characters.selectProfession(account,generation,3,character,CharacterProfession.WARRIOR));
            clear(character);sql("UPDATE dalmaru.character_profiles SET level=10 WHERE character_id=?",character);
            sql("INSERT INTO dalmaru.character_quest_progress(character_id,quest_id,content_version,state) VALUES(?,'pilot1.zombie_hunt','pilot-1','REWARDED')",character);
            reject(()->characters.selectProfession(account,generation,4,character,CharacterProfession.WARRIOR));
            complete(character,CharacterProfession.MAGE);
            reject(()->characters.selectProfession(account,generation,5,character,CharacterProfession.WARRIOR));
            UUID other=characters.create(account,generation,6,2,"other_"+account.toString().substring(0,8)).character().characterId();complete(other,CharacterProfession.WARRIOR);
            reject(()->characters.selectProfession(account,generation,7,character,CharacterProfession.WARRIOR));
            for(int i=0;i<flags.size();i++) {
                complete(character,CharacterProfession.WARRIOR);setFlag(character,flags.get(i),"ACTIVE",CharacterRepository.PROFESSION_GATE_VERSION);
                long request=10+i;reject(()->characters.selectProfession(account,generation,request,character,CharacterProfession.WARRIOR));
            }
            complete(character,CharacterProfession.WARRIOR);setFlag(character,flags.get(0),"COMPLETED","old-version");
            reject(()->characters.selectProfession(account,generation,20,character,CharacterProfession.WARRIOR));
            var forged=new JsonObject();forged.addProperty("profession","WARRIOR");forged.addProperty("villageVisited",true);
            reject(()->characters.selectProfessionJournaled(account,generation,21,character,CharacterProfession.WARRIOR,new RequestEnvelope(UUID.randomUUID(),generation,character,"select_profession",forged)));
            check(characters.profession(character)==null&&characters.profile(character).unspentSkillPoints()==0,"Rejected gate mutated profession or reward");
            complete(character,CharacterProfession.WARRIOR);
            var body=new JsonObject();body.addProperty("profession","WARRIOR");var request=new RequestEnvelope(UUID.randomUUID(),generation,character,"select_profession",body);
            try(var c=connection();var q=c.createStatement()) {
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+character+"'::uuid AND NEW.unspent_skill_points>OLD.unspent_skill_points THEN RAISE EXCEPTION 'gate reward rollback'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.character_profiles FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try{characters.selectProfessionJournaled(account,generation,30,character,CharacterProfession.WARRIOR,request);throw new AssertionError("Rollback not injected");}catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected SQL failure");}
            check(characters.profession(character)==null&&characters.profile(character).unspentSkillPoints()==0,"Gate transaction failed to roll back");drop(trap);
            var pool=Executors.newFixedThreadPool(2);
            try {
                var a=pool.submit(()->characters.selectProfessionJournaled(account,generation,31,character,CharacterProfession.WARRIOR,request));
                var b=pool.submit(()->characters.selectProfessionJournaled(account,generation,32,character,CharacterProfession.WARRIOR,request));
                check(a.get().replayed()!=b.get().replayed(),"Concurrent gate request did not execute once");
            }finally{pool.shutdownNow();}
            check(characters.profession(character)==CharacterProfession.WARRIOR&&characters.profile(character).unspentSkillPoints()==6,"Gate changed the existing skill-point reward");
            clear(character);check(characters.selectProfessionJournaled(account,generation,33,character,CharacterProfession.WARRIOR,request).replayed(),"Confirmed receipt was incorrectly gated again");
            check(characters.profile(character).unspentSkillPoints()==6,"Replay duplicated rewards");
        }finally{drop(trap);EnhancementReplayCheck.cleanup(account);}
        System.out.println("PASS profession level/village/trial/story/version/character/job/state gates; no client-asserted flags; reward rollback/concurrency/replay; actual UI requirements bounds");
    }
    private interface Action{void run()throws Exception;}
    private static void reject(Action action)throws Exception{try{action.run();throw new AssertionError("Gate accepted missing proof");}catch(IllegalArgumentException expected){}}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Connection connection()throws SQLException{return EnhancementReplayCheck.connection();}
    private static void complete(UUID character,CharacterProfession job)throws SQLException{try(var c=connection()){ProfessionGateFixture.complete(c,character,job);}}
    private static void sql(String sql,UUID id)throws SQLException{try(var c=connection();var q=c.prepareStatement(sql)){q.setObject(1,id);q.executeUpdate();}}
    private static void clear(UUID character)throws SQLException{sql("DELETE FROM dalmaru.character_quest_progress WHERE character_id=? AND content_version='pilot-2'",character);}
    private static void setFlag(UUID character,String id,String state,String version)throws SQLException{try(var c=connection();var q=c.prepareStatement("UPDATE dalmaru.character_quest_progress SET state=?,content_version=? WHERE character_id=? AND quest_id=? AND content_version=?")){q.setString(1,state);q.setString(2,version);q.setObject(3,character);q.setString(4,id);q.setString(5,CharacterRepository.PROFESSION_GATE_VERSION);q.executeUpdate();}}
    private static void drop(String name)throws SQLException{try(var c=connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+name+" ON dalmaru.character_profiles");q.execute("DROP FUNCTION IF EXISTS dalmaru."+name+"()");}}
    private static void display()throws Exception {
        String source=Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruProfessionScreen.java"));int start=source.indexOf("public void extractRenderState("),open=source.indexOf('{',start),end=open+1,depth=1;
        check(start>=0,"Missing profession requirements display");for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}
        Path dir=Files.createTempDirectory("profession-gate-display-");
        try{Path file=dir.resolve("GateDisplay.java");Files.writeString(file,DISPLAY.replace("/* METHOD */",source.substring(start,end)));
            check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),file.toString())==0,"Display fixture compile");
            try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},ProfessionGateCheck.class.getClassLoader())){loader.loadClass("GateDisplay").getMethod("run").invoke(null);}
        }finally{try(var paths=Files.walk(dir)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static final String DISPLAY="""
        import java.util.*;
        class Font{int width(String s){return s.length()*7;}String plainSubstrByWidth(String s,int w){return s.substring(0,Math.min(s.length(),w/7));}}
        class GuiGraphicsExtractor{List<String>messages=new ArrayList<>();int maxY;void text(Font f,String s,int x,int y,int color,boolean shadow){if(x<0||x+f.width(s)>320)throw new AssertionError("Clipped requirements");messages.add(s);maxY=Math.max(maxY,y+9);}}
        class Screen{int width=320,height=180;Font font=new Font();void extractRenderState(GuiGraphicsExtractor g,int x,int y,float d){}}
        public class GateDisplay extends Screen{/* METHOD */ public static void run(){var s=new GateDisplay();var g=new GuiGraphicsExtractor();s.extractRenderState(g,0,0,0);if(!g.messages.equals(List.of("전직 조건: 10레벨 · 직업 마을 방문","해당 직업 시험 · 이야기 완료"))||g.maxY>s.height/2-55)throw new AssertionError("Missing or overlapping requirements");}}
        """;
}
