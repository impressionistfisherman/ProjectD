package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.function.Consumer;
import javax.tools.ToolProvider;
import kr.dalmaru.account.*;

/** Models persisted player NBT independently from DB commit, using the actual repository. */
public final class ReviewDatabaseCheck {
    public static void main(String[] args)throws Exception {
        if(!"oscar_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Disposable database required.");
        String baseline=System.getProperty("reviewSource");
        if(baseline!=null&&!Boolean.getBoolean("reviewBaseline")) {
            Path classes=Files.createTempDirectory("dalmaru-review-baseline-");
            try {
                var inputs=new java.util.ArrayList<String>();inputs.addAll(java.util.List.of("-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
                try(var files=Files.list(Path.of(baseline).resolve("main/java/kr/dalmaru/account"))){files.filter(file->file.toString().endsWith(".java")&&!file.getFileName().toString().equals("VanillaCharacterState.java")).forEach(file->inputs.add(file.toString()));}
                if(ToolProvider.getSystemJavaCompiler().run(null,null,null,inputs.toArray(String[]::new))!=0)throw new IllegalStateException("Baseline compilation failed.");
                var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-DreviewBaseline=true","-cp",classes+java.io.File.pathSeparator+System.getProperty("java.class.path"),ReviewDatabaseCheck.class.getName()).inheritIO().start();
                if(child.waitFor()!=0)throw new AssertionError("Baseline R2 reproduced.");
            }finally{try(var files=Files.walk(classes)){for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);}}
            return;
        }
        boolean failed=false;
        for(boolean committed:new boolean[]{true,false}) {
            try{crash(committed);System.out.println("R2 "+(committed?"after":"before")+" commit PASS");}
            catch(AssertionError error){failed=true;System.out.println("R2 "+(committed?"after":"before")+" commit FAIL: "+error.getMessage());}
        }
        if(failed)throw new AssertionError("R2 DB/NBT crash mismatch reproduced.");
        if(!Boolean.getBoolean("reviewBaseline"))legacyMismatch();
    }
    private static void legacyMismatch()throws Exception {
        UUID account=UUID.randomUUID();var session=new AccountRepository().openSession(account);var repo=new CharacterRepository();
        UUID character=repo.create(account,session.connectionGeneration(),1,1,"legacy_"+account.toString().substring(0,8)).character().characterId();
        try(var connection=open()) {
            try(var update=connection.prepareStatement("UPDATE dalmaru.accounts SET active_character_id=? WHERE account_id=?")){update.setObject(1,character);update.setObject(2,account);update.executeUpdate();}
            try(var insert=connection.prepareStatement("INSERT INTO dalmaru.character_vanilla_state(character_id,state)VALUES(?,?::jsonb)")){insert.setObject(1,character);insert.setString(2,"{\"label\":\"stored\"}");insert.executeUpdate();}
        }
        boolean[] installed={false};boolean rejected=false;
        try{repo.recoverVanilla(account,session.connectionGeneration(),"{\"label\":\"NBT\"}",state->installed[0]=true);}catch(IllegalStateException expected){rejected=true;}
        require(rejected&&!installed[0],"V022 legacy mismatched NBT was adopted");
        try(var connection=open();var query=connection.prepareStatement("SELECT vanilla_owner IS NULL FROM dalmaru.accounts WHERE account_id=?")){query.setObject(1,account);try(var row=query.executeQuery()){require(row.next()&&row.getBoolean(1),"Legacy refusal changed owner");}}
        System.out.println("V022 legacy owner NULL + snapshot/NBT mismatch: fail-closed PASS (synthetic fixture).");
    }
    private static void crash(boolean commit)throws Exception {
        UUID account=UUID.randomUUID();var accounts=new AccountRepository();var session=accounts.openSession(account);var repo=new CharacterRepository();
        UUID a=repo.create(account,session.connectionGeneration(),1,1,"review_"+account.toString().substring(0,8)).character().characterId();
        UUID b=repo.create(account,session.connectionGeneration(),2,2,"reviewb_"+account.toString().substring(0,8)).character().characterId();
        String aState="{\"label\":\"A\",\"inventory\":[\"diamond\"],\"ender\":[\"emerald\"],\"slot\":0,\"level\":1,\"total\":10,\"progress\":0.0}";
        String bState="{\"label\":\"B\",\"inventory\":[\"iron\"],\"ender\":[],\"slot\":0,\"level\":2,\"total\":20,\"progress\":0.0}";
        var initial=repo.selectActive(account,session.connectionGeneration(),3,a,aState);
        String[] disk={initial.vanillaState()==null?aState:initial.vanillaState()};
        try(var connection=open();var insert=connection.prepareStatement("INSERT INTO dalmaru.character_vanilla_state(character_id,state)VALUES(?,?::jsonb) ON CONFLICT(character_id) DO UPDATE SET state=EXCLUDED.state")){insert.setObject(1,b);insert.setString(2,bState);insert.executeUpdate();}
        checkpoint(repo,account,session.connectionGeneration(),disk[0]);
        try {
            repo.selectActive(account,session.connectionGeneration(),4,b,disk[0],state->{if(!commit){disk[0]=state;throw new IllegalStateException("crash after target NBT save before commit");}});
            if(!commit)throw new AssertionError("Injected crash did not abort selection.");
        }catch(IllegalStateException error){if(commit)throw error;}
        if(!commit)require(com.google.gson.JsonParser.parseString(disk[0]).getAsJsonObject().get("label").getAsString().equals("B"),"Crash injection never persisted the target NBT.");
        var reconnected=accounts.openSession(account);
        recover(repo,account,reconnected.connectionGeneration(),disk[0],state->disk[0]=state);
        String expected=commit?"B":"A";
        require(com.google.gson.JsonParser.parseString(disk[0]).getAsJsonObject().get("label").getAsString().equals(expected),"active "+expected+" retained another character's NBT");
        UUID target=commit?a:b;
        checkpoint(repo,account,reconnected.connectionGeneration(),disk[0]);
        repo.selectActive(account,reconnected.connectionGeneration(),5,target,disk[0]);
        try(var connection=open();var query=connection.prepareStatement("SELECT state->>'label' FROM dalmaru.character_vanilla_state WHERE character_id=?")){query.setObject(1,commit?b:a);try(var row=query.executeQuery()){require(row.next()&&expected.equals(row.getString(1)),"switch after crash polluted stored ownership");}}
    }
    private static void checkpoint(CharacterRepository repo,UUID account,long generation,String snapshot)throws Exception {
        try{repo.getClass().getMethod("checkpointVanilla",UUID.class,long.class,String.class).invoke(repo,account,generation,snapshot);}
        catch(NoSuchMethodException absent){/* The baseline has no durable source checkpoint. */}
    }
    private static void recover(CharacterRepository repo,UUID account,long generation,String snapshot,Consumer<String> install)throws Exception {
        try{repo.getClass().getMethod("recoverVanilla",UUID.class,long.class,String.class,Consumer.class).invoke(repo,account,generation,snapshot,install);}
        catch(NoSuchMethodException absent){/* Baseline JOIN leaves NBT untouched. */}
    }
    private static java.sql.Connection open()throws Exception {return DriverManager.getConnection("jdbc:postgresql://"+System.getenv("DALMARU_DB_HOST")+":"+System.getenv("DALMARU_DB_PORT")+"/"+System.getenv("DALMARU_DB_NAME"),System.getenv("DALMARU_DB_USER"),System.getenv("DALMARU_DB_PASSWORD"));}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
