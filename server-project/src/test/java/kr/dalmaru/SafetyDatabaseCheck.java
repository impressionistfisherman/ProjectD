package kr.dalmaru;
import java.util.UUID;
import kr.dalmaru.account.*;
public final class SafetyDatabaseCheck {
    public static void main(String[]args)throws Exception {
        if(!"oscar_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Disposable database required.");
        UUID account=UUID.randomUUID();var accounts=new AccountRepository();var session=accounts.openSession(account);var characters=new CharacterRepository();
        UUID character=characters.create(account,session.connectionGeneration(),1,1,"safe_"+account.toString().substring(0,8)).character().characterId();
        characters.selectActive(account,session.connectionGeneration(),2,character);
        var safety=new SafetyRepository();var point=new SafeCheckpoint("town:first","minecraft:overworld",.5,65,.5);
        safety.activate(account,session.connectionGeneration(),character,point);
        require(point.equals(safety.load(character)),"Checkpoint persistence");
        reject(()->safety.activate(account,session.connectionGeneration()+1,character,new SafeCheckpoint("bad","minecraft:overworld",5,65,5)));
        reject(()->safety.activate(UUID.randomUUID(),session.connectionGeneration(),character,point));
        require(point.equals(safety.load(character)),"Rejected owner changed checkpoint");
        var reopened=accounts.openSession(account);
        reject(()->safety.activate(account,session.connectionGeneration(),character,point));
        var second=new SafeCheckpoint("town:second","minecraft:overworld",2.5,65,2.5);
        safety.activate(account,reopened.connectionGeneration(),character,second);
        require(second.equals(safety.load(character)),"New generation checkpoint replacement");
        System.out.println("C05/06 checkpoint persistence/owner/stale generation PASS; safe floor requires headless.");
    }
    private interface Operation{void run()throws Exception;}
    private static void reject(Operation operation)throws Exception{try{operation.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Unsafe owner accepted");}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
