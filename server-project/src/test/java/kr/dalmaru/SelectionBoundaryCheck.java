package kr.dalmaru;

import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.ToolProvider;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.run.RunRepository;

/** Reviewer JDBC proxy: COMMIT applies then loses its reply; a delayed ACK sees a newer terminal run. */
public final class SelectionBoundaryCheck {
    private static final UUID account=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID(),oldRun=UUID.randomUUID();
    private static UUID persisted=a;private static int connections;private static boolean oldPending=true,newPending=true,newTerminal;private static String installedState="{\"owner\":\"old-owner\"}";
    public static void main(String[] args)throws Exception {
        Path root=Path.of(System.getProperty("reviewSource","src"));
        if(System.getProperty("reviewSource")!=null&&!Boolean.getBoolean("boundaryChild")) {
            Path classes=Files.createTempDirectory("dalmaru-boundary-baseline-");
            try {
                var inputs=new ArrayList<String>(List.of("-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
                try(var files=Files.list(root.resolve("main/java/kr/dalmaru/account"))){files.filter(file->file.toString().endsWith(".java")&&!file.getFileName().toString().equals("VanillaCharacterState.java")).forEach(file->inputs.add(file.toString()));}
                inputs.add(root.resolve("main/java/kr/dalmaru/run/RunRepository.java").toString());
                if(ToolProvider.getSystemJavaCompiler().run(null,null,null,inputs.toArray(String[]::new))!=0)throw new AssertionError("Baseline compile failed.");
                var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-DboundaryChild=true","-DreviewSource="+root,"-cp",classes+java.io.File.pathSeparator+System.getProperty("java.class.path"),SelectionBoundaryCheck.class.getName()).inheritIO().start();
                if(process.waitFor()!=0)throw new AssertionError("Baseline boundary reproduced.");
            }finally{erase(classes);}
            return;
        }
        DriverManager.registerDriver(new FakeDriver());
        String network=Files.readString(root.resolve("main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        int start=network.indexOf("CharacterSelectRequest.TYPE");int failed=network.indexOf("} catch(Exception error)",start);int log=network.indexOf("LOGGER.warn",failed);
        String catchBody=network.substring(network.indexOf('{',failed)+1,log);
        String policy="";if(network.contains("private static boolean closeUncertainSelection("))policy=method(network,"private static boolean closeUncertainSelection(");
        Path fixture=Files.createTempDirectory("dalmaru-selection-policy-");List<String>failures=new ArrayList<>();
        try {
            String code=POLICY.replace("/* POLICY */",policy).replace("/* CATCH */",catchBody).replace("installed[0]","installed").replace("kr.dalmaru.account.VanillaCharacterState","VanillaCharacterState");
            Path file=fixture.resolve("SelectionPolicy.java");Files.writeString(file,code);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",fixture.toString(),file.toString())!=0)throw new AssertionError("Policy compile failed.");
            try(var loader=new URLClassLoader(new java.net.URL[]{fixture.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
                Method check=loader.loadClass("SelectionPolicy").getMethod("check",String.class,String.class,Exception.class,boolean.class);
                String original=installedState;boolean[]installed={false};Exception failure=null;
                try{new CharacterRepository().selectActive(account,1,1,b,original,state->{installed[0]=true;installedState=state;});}catch(Exception error){failure=error;}
                if(failure==null||!persisted.equals(b)||!installed[0])throw new AssertionError("COMMIT loss was not injected after DB ownership B.");
                try{check.invoke(null,original,installedState,failure,true);System.out.println("N4 COMMIT reply loss PASS: live transfer blocked, unowned quarantine requires DB recovery.");}catch(InvocationTargetException error){failures.add("N4");System.out.println("N4 FAIL: "+error.getCause().getMessage());}
                connections=0;persisted=a;installedState=original;
                try{new CharacterRepository().selectActive(account,1,2,b,original,state->{installedState=state;throw new IllegalArgumentException("installation rejected before COMMIT");});throw new AssertionError("Expected install rejection.");}catch(IllegalArgumentException error){check.invoke(null,original,installedState,error,false);}
                System.out.println("N4 confirmed rollback original restoration PASS");
            }
            ExecutorService queue=Executors.newSingleThreadExecutor();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            try {
                queue.submit(()->{entered.countDown();try{release.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}});if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("Queue did not wait.");
                var repo=new RunRepository();Future<?> ack=queue.submit(()->{try{ack(repo,1);}catch(Exception error){throw new RuntimeException(error);}});
                newTerminal=true;release.countDown();ack.get(3,TimeUnit.SECONDS);
                if(oldPending||!newPending){failures.add("N5");System.out.println("N5 FAIL: old ACK deleted a newer pending return.");}else System.out.println("N5 delayed old-run ACK PASS: new terminal return preserved.");
                oldPending=true;ack(repo,2);if(!oldPending){failures.add("N5 generation");System.out.println("N5 FAIL: stale-generation ACK accepted.");}else System.out.println("N5 stale-generation ACK PASS");
            }finally{release.countDown();queue.shutdownNow();if(!queue.awaitTermination(3,TimeUnit.SECONDS))throw new AssertionError("Queue leaked.");}
        }finally{erase(fixture);}
        if(!failures.isEmpty())throw new AssertionError("Boundary regressions: "+failures);
    }
    private static void ack(RunRepository repo,long generation)throws Exception {
        try{repo.getClass().getMethod("acknowledgeReturn",UUID.class,long.class,UUID.class,UUID.class).invoke(repo,account,generation,a,oldRun);}
        catch(NoSuchMethodException old){repo.getClass().getMethod("acknowledgeReturn",UUID.class).invoke(repo,a);}
    }
    private static String method(String source,String signature){int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,end=open+1;for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}return source.substring(start,end);}
    private static void erase(Path root)throws Exception {try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
    private static Object fallback(Class<?>type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    private static <T>T proxy(Class<T>type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}
    private static ResultSet rows(List<Object[]> data){int[]row={-1};return proxy(ResultSet.class,(p,m,args)->{if(m.getName().equals("next"))return ++row[0]<data.size();if(m.getName().equals("getObject"))return data.get(row[0])[(Integer)args[0]-1];if(m.getName().equals("getString")){Object value=data.get(row[0])[(Integer)args[0]-1];return value==null?null:value.toString();}return fallback(m.getReturnType());});}
    private static Connection connection(){int n=++connections;UUID[]selected={persisted};return proxy(Connection.class,(p,m,args)->{
        String op=m.getName();if(op.equals("prepareStatement")){String sql=(String)args[0];Map<Integer,Object>params=new HashMap<>();return proxy(PreparedStatement.class,(q,f,v)->{
            String action=f.getName();if(action.startsWith("set")&&v!=null){params.put((Integer)v[0],v[1]);return null;}
            if(action.equals("executeUpdate")){
                if(sql.startsWith("UPDATE dalmaru.accounts SET active_character_id"))selected[0]=(UUID)params.get(1);
                if(sql.startsWith("UPDATE dalmaru.run_participants p SET return_pending")) {
                    if(sql.contains("p.run_id=?")){if(!oldRun.equals(params.get(1))||!a.equals(params.get(2))||!account.equals(params.get(3))||!Long.valueOf(1).equals(params.get(4)))return 0;oldPending=false;}
                    else{oldPending=false;if(newTerminal)newPending=false;}
                }
                return 1;
            }
            if(action.equals("executeQuery")){List<Object[]>data=new ArrayList<>();if(sql.startsWith("SELECT active_character_id,vanilla_owner"))data.add(new Object[]{a,"old-owner"});else if(sql.startsWith("SELECT active_character_id FROM"))data.add(new Object[]{a});else if(sql.startsWith("SELECT state::text"))data.add(new Object[]{"{\"owner\":\"target-owner\",\"item\":\"B\"}"});return rows(data);}return fallback(f.getReturnType());
        });}
        if(op.equals("commit")&&n==2){persisted=selected[0];throw new SQLException("COMMIT applied; reply lost","08006");}return fallback(m.getReturnType());
    });}
    public static final class FakeDriver implements Driver {
        public boolean acceptsURL(String url){return url.startsWith("jdbc:postgresql:");}public Connection connect(String url,Properties properties){return acceptsURL(url)?connection():null;}public DriverPropertyInfo[]getPropertyInfo(String url,Properties p){return new DriverPropertyInfo[0];}public int getMajorVersion(){return 1;}public int getMinorVersion(){return 0;}public boolean jdbcCompliant(){return false;}public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
    }
    private static final String POLICY="""
        class Connection{boolean disconnected;void disconnect(String message){disconnected=true;}}
        class ServerPlayer{String inventory;Connection connection=new Connection();ServerPlayer(String state){inventory=state;}}
        class ConnectionState{boolean compatible=true;String accountFailure;}
        class Component{static String literal(String message){return message;}}
        class VanillaCharacterState{static void restore(ServerPlayer player,String state){player.inventory=state;}static void quarantine(ServerPlayer player){player.inventory="unowned quarantine";}}
        public class SelectionPolicy {
            static class Log{void warn(String message,Exception error){}}static final Log LOGGER=new Log();
            /* POLICY */
            static void failed(ServerPlayer player,ConnectionState state,String original,boolean installed,boolean committed,Exception error) { /* CATCH */ }
            public static void check(String original,String target,Exception error,boolean uncertain) {
                var player=new ServerPlayer(target);var state=new ConnectionState();failed(player,state,original,true,false,error);
                if(uncertain){if(!player.connection.disconnected||state.compatible||state.accountFailure==null||player.inventory.equals(original)||player.inventory.equals(target))throw new AssertionError("COMMIT uncertainty kept transferable owned inventory");}
                else if(player.connection.disconnected||!player.inventory.equals(original))throw new AssertionError("Confirmed rollback did not preserve original playable state");
            }
        }
        """;
}
