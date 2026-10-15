package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.Comparator;
import javax.tools.ToolProvider;

public final class QuestAcceptanceWireCheck {
    public static void main(String[] args)throws Exception {
        String source=Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruClient.java"));
        int start=source.indexOf("private static void acceptQuestOperation("),open=source.indexOf('{',start),depth=1,end=open+1;
        if(start<0||open<0)throw new AssertionError("Missing quest acceptance handler");
        while(depth>0){char value=source.charAt(end++);if(value=='{')depth++;if(value=='}')depth--;}
        String code=HARNESS.replace("/* RECEIPT */",source.substring(start,end));
        Path directory=Files.createTempDirectory("quest-acceptance-wire-");
        try {
            Path file=directory.resolve("QuestAcceptanceWireProof.java");Files.writeString(file,code);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",directory.toString(),file.toString())!=0)throw new AssertionError("Quest acceptance receipt fixture compile failed");
            try(var loader=new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},QuestAcceptanceWireCheck.class.getClassLoader())){loader.loadClass("QuestAcceptanceWireProof").getMethod("run").invoke(null);}
        }finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("Actual quest acceptance receipt replay/reject/malformed result, no original-state application and latest query PASS");
    }
    private static final String HARNESS="""
        import java.util.*;
        class DalmaruPayloads {
            record OperationRequest(long correlationId,UUID requestId,long connectionGeneration,UUID activeCharacterId,String operation,String payload){}
            record OperationReceipt(long correlationId,UUID requestId,UUID eventId,String state,String result,boolean replayed){}
            record QuestResponse(long requestId,String status){}
        }
        class ClientQuestState {static String status="REWARDED",stage="REWARDED";static void accept(DalmaruPayloads.QuestResponse response){if(response.requestId()==7)status=response.status();}}
        public class QuestAcceptanceWireProof {
            static int queries;
            static void requestQuest(boolean accept){if(accept)throw new AssertionError("Receipt restarted quest");queries++;}
            /* RECEIPT */
            static void check(boolean value){if(!value)throw new AssertionError("Quest acceptance receipt boundary");}
            public static void run(){
                UUID request=UUID.randomUUID(),character=UUID.randomUUID(),event=UUID.randomUUID();
                var body=new com.google.gson.JsonObject();body.addProperty("questId","pilot1.zombie_hunt");body.addProperty("contentVersion","pilot-1");
                var operation=new DalmaruPayloads.OperationRequest(7,request,1,character,"accept_quest",body.toString());var result=body.deepCopy();result.addProperty("created",true);
                acceptQuestOperation(operation,new DalmaruPayloads.OperationReceipt(7,request,event,"pending_recovery",result.toString(),true));check(queries==1&&ClientQuestState.stage.equals("REWARDED")&&ClientQuestState.status.equals("REWARDED"));
                for(int invalid=0;invalid<4;invalid++){
                    var bad=result.deepCopy();switch(invalid){case 0->bad.remove("created");case 1->bad.addProperty("created","true");case 2->bad.addProperty("questId","other.quest");case 3->bad.addProperty("contentVersion","other-version");}
                    acceptQuestOperation(operation,new DalmaruPayloads.OperationReceipt(7,request,event,"completed",bad.toString(),false));check(ClientQuestState.status.contains("확인에 실패")&&ClientQuestState.stage.equals("REWARDED"));
                }
                acceptQuestOperation(operation,new DalmaruPayloads.OperationReceipt(7,request,new UUID(0,0),"rejected","{}",false));check(ClientQuestState.status.contains("조건")&&queries==5);
                acceptQuestOperation(operation,new DalmaruPayloads.OperationReceipt(8,request,new UUID(0,0),"rejected","{}",false));check(queries==5&&ClientQuestState.stage.equals("REWARDED"));
            }
        }
        """;
}
