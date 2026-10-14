package kr.dalmaru;

import com.google.gson.JsonParser;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.UUID;
import kr.dalmaru.request.RequestJournal;

public final class RequestJournalResultCheck {
    public static void main(String[] args)throws Exception{
        Connection connection=(Connection)Proxy.newProxyInstance(RequestJournalResultCheck.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,arguments)->{
            if(method.getName().equals("getAutoCommit"))return false;
            throw new AssertionError("Confirmed result check must not issue SQL: "+method.getName());
        });
        var original=JsonParser.parseString("{\"amount\":9223372036854775806}").getAsJsonObject();
        var changed=JsonParser.parseString("{\"amount\":9223372036854775807}").getAsJsonObject();
        var receipt=new RequestJournal.Receipt(UUID.randomUUID(),UUID.randomUUID(),"completed");
        var stored=new RequestJournal.Stored(receipt,1,original);
        if(!RequestJournal.confirm(connection,stored,original).equals(receipt))throw new AssertionError("Identical large integer result rejected");
        boolean rejected=false;try{RequestJournal.confirm(connection,stored,changed);}catch(IllegalArgumentException expected){rejected=true;}
        if(!rejected)throw new AssertionError("Different large integers treated as one confirmed result");
        System.out.println("Confirmed large integer result remains exact; changed result rejected without SQL PASS");
    }
}
