package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.*;
import javax.tools.ToolProvider;
import kr.dalmaru.account.AccountRepository;

/** Holds a real account row lock; timeout must reject rather than wait indefinitely or issue a generation. */
public final class LifecycleDatabaseCheck {
    public static void main(String[] args)throws Exception {
        if(!"oscar_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Disposable database required.");
        String baseline=System.getProperty("reviewSource");
        if(baseline!=null&&!Boolean.getBoolean("reviewBaseline")) {
            Path classes=Files.createTempDirectory("dalmaru-lifecycle-baseline-");
            try {
                var inputs=new java.util.ArrayList<String>();inputs.addAll(java.util.List.of("-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
                try(var files=Files.list(Path.of(baseline).resolve("main/java/kr/dalmaru/account"))){files.filter(file->file.toString().endsWith(".java")&&!file.getFileName().toString().equals("VanillaCharacterState.java")).forEach(file->inputs.add(file.toString()));}
                if(ToolProvider.getSystemJavaCompiler().run(null,null,null,inputs.toArray(String[]::new))!=0)throw new IllegalStateException("Baseline compilation failed.");
                var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-DreviewBaseline=true","-cp",classes+java.io.File.pathSeparator+System.getProperty("java.class.path"),LifecycleDatabaseCheck.class.getName()).inheritIO().start();
                if(child.waitFor()!=0)throw new AssertionError("Baseline N1 lock reproduced.");
            }finally{try(var files=Files.walk(classes)){for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);}}
            return;
        }
        UUID id=UUID.randomUUID();var repo=new AccountRepository();repo.openSession(id);var executor=Executors.newSingleThreadExecutor();boolean timedOut=false;long elapsed;
        try(var lock=open()) {
            lock.setAutoCommit(false);
            try(var statement=lock.prepareStatement("SELECT 1 FROM dalmaru.accounts WHERE account_id=? FOR UPDATE")){statement.setObject(1,id);statement.executeQuery().close();}
            long started=System.nanoTime();var future=executor.submit(()->repo.openSession(id));
            try{future.get(800,TimeUnit.MILLISECONDS);throw new AssertionError("Locked session unexpectedly succeeded.");}
            catch(TimeoutException error){timedOut=true;}
            catch(ExecutionException error){if(!(error.getCause() instanceof java.sql.SQLException))throw error;}
            elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);lock.rollback();
            if(timedOut)future.get(3,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();if(!executor.awaitTermination(4,TimeUnit.SECONDS))throw new IllegalStateException("Lock test thread leaked.");}
        if(timedOut){System.out.println("N1 lock FAIL: JDBC still waited after 800ms");throw new AssertionError("No bounded query/lock timeout.");}
        try(var connection=open();var query=connection.prepareStatement("SELECT connection_generation FROM dalmaru.accounts WHERE account_id=?")){query.setObject(1,id);try(var row=query.executeQuery()){if(!row.next()||row.getLong(1)!=1)throw new AssertionError("Timed out session issued a generation.");}}
        var field=AccountRepository.class.getDeclaredField("url");field.setAccessible(true);String url=(String)field.get(repo);
        if(!url.contains("connectTimeout=1")||!url.contains("socketTimeout=2")||!url.contains("statement_timeout")||!url.contains("lock_timeout"))throw new AssertionError("Missing network/query deadline.");
        System.out.println("N1 lock PASS: rejected in "+elapsed+"ms, generation unchanged; connect/socket/query/lock limits configured.");
    }
    private static java.sql.Connection open()throws Exception{return DriverManager.getConnection("jdbc:postgresql://"+System.getenv("DALMARU_DB_HOST")+":"+System.getenv("DALMARU_DB_PORT")+"/"+System.getenv("DALMARU_DB_NAME"),System.getenv("DALMARU_DB_USER"),System.getenv("DALMARU_DB_PASSWORD"));}
}
