package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** Exercises the actual receipt parser and pending-skill completion without a game client. */
public final class ResetWireReceiptCheck {
    public static void main(String[] args)throws Exception {
        String client=Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruClient.java"));
        String profile=Files.readString(Path.of("src/client/java/kr/dalmaru/client/ClientProfileState.java"));
        String code=HARNESS.replace("/* RECEIPT */",method(client,"private static void acceptResetOperation(")).replace("/* COMPLETE */",method(profile,"static void completeReset(")+method(profile,"static void delegateResetRetry(")+method(profile,"static DalmaruPayloads.CharacterStatResetRequest retryReset("));
        Path directory=Files.createTempDirectory("reset-wire-receipt-");
        try {
            Path file=directory.resolve("ResetWireProof.java");Files.writeString(file,code);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",directory.toString(),file.toString())!=0)throw new AssertionError("Receipt fixture compilation");
            try(var loader=new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},ResetWireReceiptCheck.class.getClassLoader())){loader.loadClass("ResetWireProof").getMethod("run").invoke(null);}
        }finally{try(var paths=Files.walk(directory)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
        System.out.println("Actual reset receipt replay/reject/malformed/correlation and exclusive retry PASS");
    }
    private static String method(String source,String signature){int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,end=open+1;if(start<0||open<0)throw new AssertionError("Method not found");while(depth>0){char x=source.charAt(end++);if(x=='{')depth++;if(x=='}')depth--;}return source.substring(start,end);}
    private static final String HARNESS="""
        import java.util.*;
        class DalmaruPayloads {
            record OperationRequest(long correlationId,UUID requestId,long connectionGeneration,UUID activeCharacterId,String operation,String payload){}
            record OperationReceipt(long correlationId,UUID requestId,UUID eventId,String state,String result,boolean replayed){}
            record CharacterStatResetRequest(long requestId,UUID characterId){}
        }
        class ClientProfileState {
            static boolean resetWaiting;static long currentResetRequestId;static int tick,resetDeadline;static String status;
            static DalmaruPayloads.CharacterStatResetRequest resetRequest;
            /* COMPLETE */
        }
        public class ResetWireProof {
            /* RECEIPT */
            static void require(boolean value){if(!value)throw new AssertionError("Reset receipt boundary");}
            static void pending(long id){ClientProfileState.resetWaiting=true;ClientProfileState.currentResetRequestId=id;ClientProfileState.resetRequest=new DalmaruPayloads.CharacterStatResetRequest(id,UUID.randomUUID());}
            public static void run(){
                UUID request=UUID.randomUUID(),character=UUID.randomUUID(),event=UUID.randomUUID();
                var original=new DalmaruPayloads.OperationRequest(7,request,1,character,"reset_stats","{}");
                String good="{\\\"strength\\\":0,\\\"dexterity\\\":0,\\\"arcana\\\":0,\\\"vitality\\\":0,\\\"spirit\\\":0,\\\"unspentStatPoints\\\":15,\\\"freeResetAvailable\\\":false}";
                pending(7);require(ClientProfileState.retryReset()!=null);ClientProfileState.delegateResetRetry();ClientProfileState.tick+=201;require(ClientProfileState.retryReset()==null&&ClientProfileState.resetWaiting);
                acceptResetOperation(original,new DalmaruPayloads.OperationReceipt(7,request,event,"pending_recovery",good,true));require(!ClientProfileState.resetWaiting&&ClientProfileState.resetRequest==null&&ClientProfileState.status.contains("최신"));
                pending(7);acceptResetOperation(original,new DalmaruPayloads.OperationReceipt(8,request,event,"pending_recovery",good,false));require(ClientProfileState.resetWaiting);
                for(String result:List.of("{}",good.replace(":0",":1"),good.replace(":15",":1.5"),good.replace(":15",":-1"),good.replace("false","true"),good.replace("false","1"))){
                    pending(7);acceptResetOperation(original,new DalmaruPayloads.OperationReceipt(7,request,event,"completed",result,false));require(!ClientProfileState.resetWaiting&&ClientProfileState.status.contains("확인"));
                }
                pending(7);acceptResetOperation(original,new DalmaruPayloads.OperationReceipt(7,request,new UUID(0,0),"rejected","{}",false));require(!ClientProfileState.resetWaiting);
            }
        }
        """;
}
