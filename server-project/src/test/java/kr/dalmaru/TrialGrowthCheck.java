package kr.dalmaru;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
import kr.dalmaru.account.*;
import kr.dalmaru.request.RequestEnvelope;
import com.google.gson.JsonObject;

public final class TrialGrowthCheck {
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();
        display();
        UUID account=UUID.randomUUID();String trap="trial_release_"+account.toString().replace("-","");
        try {
            var characters=new CharacterRepository();long generation=new AccountRepository().openSession(account).connectionGeneration();
            UUID early=characters.create(account,generation,1,1,"early_"+account.toString().substring(0,8)).character().characterId();
            characters.selectActive(account,generation,2,early);
            var ten=characters.grantExperience(early,xp(1,10));check(ten.level()==10&&ten.unspentSkillPoints()==0,"Pre-profession level rewards");
            quest(early);characters.selectProfession(account,generation,3,early,CharacterProfession.WARRIOR);
            check(characters.profile(early).unspentSkillPoints()==6,"Initial profession reward");
            var nineteen=characters.grantExperience(early,xp(10,19));check(nineteen.level()==19&&nineteen.unspentSkillPoints()==15,"11 through 19 level points");
            var capped=characters.grantExperience(early,xp(19,21)+17);
            check(capped.level()==20&&capped.experience()==CharacterRepository.experienceNeededForNextLevel(20)+17&&capped.unspentStatPoints()==95&&capped.unspentSkillPoints()==16,"Cap stops levels, retains overflow, rewards level20 once");
            var stored=characters.grantExperience(early,500);
            check(stored.level()==20&&stored.experience()==capped.experience()+500&&stored.unspentSkillPoints()==16&&stored.unspentStatPoints()==95,"Repeated cap rewards only add XP");
            try{characters.grantExperience(early,Long.MAX_VALUE);throw new AssertionError("Overflow accepted");}catch(ArithmeticException expected){}
            check(characters.profile(early).equals(stored),"Overflow must not mutate profile");
            try{characters.grantExperience(early,0);throw new AssertionError("Zero XP accepted");}catch(IllegalArgumentException expected){}
            long releaseXp=xp(20,25)+37;
            var waiting=characters.grantExperience(early,releaseXp-stored.experience());
            check(waiting.level()==20&&waiting.experience()==releaseXp,"Trial never calls release recalculation");
            try(var c=connection();var q=c.createStatement()) {
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+early+"'::uuid AND NEW.level>20 THEN RAISE EXCEPTION 'release rollback'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.character_profiles FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try{release(early);throw new AssertionError("Rollback not injected");}catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected SQL failure");}
            check(characters.profile(early).equals(waiting),"Release failure must retain stored XP and points");
            drop(trap);
            var released=release(early);
            check(released.level()==25&&released.experience()==37&&released.unspentStatPoints()==120&&released.unspentSkillPoints()==21,"Release consumes buffered XP and grants ordinary level points");
            var repeat=release(early);
            check(repeat.level()==25&&repeat.experience()==37&&repeat.unspentStatPoints()==120&&repeat.unspentSkillPoints()==21,"Repeated release cannot duplicate rewards");
            characters.grantExperience(early,xp(25,60)+59-repeat.experience());
            var sixty=release(early);
            check(sixty.level()==60&&sixty.experience()==59&&sixty.unspentStatPoints()==295&&sixty.unspentSkillPoints()==56,"Release level60 without P5 milestone bonuses or XP loss");
            UUID late=characters.create(account,generation,10,2,"late_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,11,late);
            var deferred=characters.grantExperience(late,xp(1,19));check(deferred.level()==19&&deferred.unspentSkillPoints()==0,"Unselected profession defers level points");quest(late);
            var body=new JsonObject();body.addProperty("profession","WARRIOR");var envelope=new RequestEnvelope(UUID.randomUUID(),generation,late,"select_profession",body);
            var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
            try {
                var a=pool.submit(()->{start.await();return characters.grantExperience(late,xp(19,20));});
                var b=pool.submit(()->{start.await();return characters.selectProfessionJournaled(account,generation,12,late,CharacterProfession.WARRIOR,envelope);});
                start.countDown();a.get();b.get();
            }finally{pool.shutdownNow();}
            check(characters.profile(late).level()==20&&characters.profile(late).unspentSkillPoints()==16,"Concurrent late profession and levelup must total16 points");
            var replay=characters.selectProfessionJournaled(account,generation,13,late,CharacterProfession.WARRIOR,envelope);check(replay.replayed()&&replay.profile().unspentSkillPoints()==16,"UUID replay cannot grant deferred points twice");
            characters.selectProfession(account,generation,14,late,CharacterProfession.WARRIOR);
            check(characters.profile(late).unspentSkillPoints()==16,"Distinct request cannot repeat profession reward");
        }finally{drop(trap);cleanup(account);}
        System.out.println("PASS trial20 cap/XP retention/11-20 points/late profession concurrency/replay/overflow; explicit release25/60/idempotency/rollback; actual cap notice render; fixture cleanup");
    }
    private static CharacterProfile release(UUID character)throws SQLException {
        try(var c=connection()){c.setAutoCommit(false);try{var p=CharacterRepository.recalculateLevelForRelease(c,character);c.commit();return p;}catch(SQLException|RuntimeException e){c.rollback();throw e;}}
    }
    private static long xp(int from,int to){long total=0;for(int level=from;level<to;level++)total+=CharacterRepository.experienceNeededForNextLevel(level);return total;}
    private static Connection connection()throws SQLException{return DriverManager.getConnection("jdbc:postgresql://localhost:15432/"+FixtureDatabase.databaseName(),"postgres",System.getenv("DALMARU_DB_PASSWORD"));}
    private static void quest(UUID character)throws SQLException {try(var c=connection();var q=c.prepareStatement("INSERT INTO dalmaru.character_quest_progress(character_id,quest_id,content_version,state) VALUES(?,'pilot1.zombie_hunt','pilot-1','REWARDED')")){q.setObject(1,character);q.executeUpdate();ProfessionGateFixture.complete(c,character,CharacterProfession.WARRIOR);}}
    private static void drop(String name)throws SQLException {try(var c=connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+name+" ON dalmaru.character_profiles");q.execute("DROP FUNCTION IF EXISTS dalmaru."+name+"()");}}
    private static void cleanup(UUID account)throws SQLException {
        try(var c=connection()){c.setAutoCommit(false);try{for(String sql:List.of("UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?","DELETE FROM dalmaru.character_selection_requests WHERE account_id=?","DELETE FROM dalmaru.character_creation_requests WHERE account_id=?","DELETE FROM dalmaru.character_profiles WHERE character_id IN(SELECT character_id FROM dalmaru.characters WHERE account_id=?)","DELETE FROM dalmaru.characters WHERE account_id=?","DELETE FROM dalmaru.accounts WHERE account_id=?")){try(var q=c.prepareStatement(sql)){q.setObject(1,account);q.executeUpdate();}}c.commit();}catch(SQLException e){c.rollback();throw e;}}
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void display()throws Exception {
        String source=Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruProfileScreen.java"));int start=source.indexOf("public void extractRenderState("),open=source.indexOf('{',start),end=open+1,depth=1;
        check(start>=0,"Missing cap display");for(;depth>0;end++){char ch=source.charAt(end);if(ch=='{')depth++;else if(ch=='}')depth--;}
        Path dir=Files.createTempDirectory("trial-growth-display-");
        try {
            Path file=dir.resolve("CapDisplay.java");Files.writeString(file,DISPLAY.replace("/* METHOD */",source.substring(start,end)));
            check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),file.toString())==0,"Cap display compilation");
            try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},TrialGrowthCheck.class.getClassLoader())){loader.loadClass("CapDisplay").getMethod("run").invoke(null);}
        }finally{try(var paths=Files.walk(dir)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static final String DISPLAY="""
        class Font{int width(String s){return s.length()*7;}String plainSubstrByWidth(String s,int width){return s.substring(0,Math.min(s.length(),width/7));}}
        class Button{int getY(){return 14;}}
        class GuiGraphicsExtractor{int calls,x,y;String message;void text(Font f,String s,int x,int y,int color,boolean shadow){calls++;message=s;this.x=x;this.y=y;}}
        class Screen{int width=320;Font font=new Font();void extractRenderState(GuiGraphicsExtractor g,int x,int y,float d){}}
        record Profile(int level){}record Response(Profile profile){}
        class ClientProfileState{static Response response;static Response current(){return response;}}
        public class CapDisplay extends Screen{
            Button statusButton=new Button();/* METHOD */
            public static void run(){var screen=new CapDisplay();var g=new GuiGraphicsExtractor();
                screen.extractRenderState(g,0,0,0);if(g.calls!=0)throw new AssertionError("Null profile displayed cap");
                ClientProfileState.response=new Response(new Profile(19));screen.extractRenderState(g,0,0,0);if(g.calls!=0)throw new AssertionError("Level19 displayed cap");
                ClientProfileState.response=new Response(new Profile(20));screen.extractRenderState(g,0,0,0);
                if(g.calls!=1||!g.message.equals("시험 상한 도달, 초과 경험치 보관 중")||g.x<0||g.x+screen.font.width(g.message)>320||g.y+9>screen.statusButton.getY())throw new AssertionError("Cap notice text or bounds");
            }
        }
        """;
}
