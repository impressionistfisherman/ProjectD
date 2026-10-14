package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** Exercises the actual receipt parser and pending-skill completion without a game client. */
public final class StatWireReceiptCheck {
    public static void main(String[] args)throws Exception {
        String client=Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruClient.java"));
        String profile=Files.readString(Path.of("src/client/java/kr/dalmaru/client/ClientProfileState.java"));
        String code=HARNESS.replace("/* RECEIPT */",method(client,"private static void acceptStatOperation(")).replace("/* COMPLETE */",method(profile,"static void completeInvestment(")+method(profile,"static void delegateInvestmentRetry(")+method(profile,"static void tick("));
        Path directory=Files.createTempDirectory("stat-wire-receipt-");
        try {
            Path file=directory.resolve("StatWireProof.java");Files.writeString(file,code);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",directory.toString(),file.toString())!=0)throw new AssertionError("Receipt fixture compilation");
            try(var loader=new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},StatWireReceiptCheck.class.getClassLoader())){loader.loadClass("StatWireProof").getMethod("run").invoke(null);}
        }finally{try(var paths=Files.walk(directory)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
        System.out.println("Actual stat receipt replay/reject/malformed/correlation and exclusive retry PASS");
    }
    private static String method(String source,String signature){int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,end=open+1;if(start<0||open<0)throw new AssertionError("Method not found");while(depth>0){char x=source.charAt(end++);if(x=='{')depth++;if(x=='}')depth--;}return source.substring(start,end);}
    private static final String HARNESS="""
        import java.util.*;
        class DalmaruPayloads {
            record OperationRequest(long correlationId,UUID requestId,long connectionGeneration,UUID activeCharacterId,String operation,String payload){}
            record OperationReceipt(long correlationId,UUID requestId,UUID eventId,String state,String result,boolean replayed){}
        }
        class ClientProfileState {
            static boolean investmentWaiting,investmentDelegated;static long currentInvestmentRequestId;static int tick,investmentDeadlineTick;static String status;
            /* COMPLETE */
        }
        public class StatWireProof {
            /* RECEIPT */
            static void require(boolean value){if(!value)throw new AssertionError("Stat receipt boundary");}
            static void pending(long id){ClientProfileState.investmentWaiting=true;ClientProfileState.currentInvestmentRequestId=id;}
            public static void run(){
                UUID request=UUID.randomUUID(),character=UUID.randomUUID(),event=UUID.randomUUID();
                var original=new DalmaruPayloads.OperationRequest(7,request,1,character,"invest_stat","{\\\"statId\\\":1,\\\"amount\\\":1}");
                pending(7);ClientProfileState.delegateInvestmentRetry();
                for(int i=0;i<201;i++)ClientProfileState.tick();require(ClientProfileState.investmentWaiting);
                acceptStatOperation(original,new DalmaruPayloads.OperationReceipt(7,request,event,"pending_recovery","{\\\"statId\\\":1,\\\"amount\\\":1,\\\"strength\\\":2}",true));
                require(!ClientProfileState.investmentWaiting&&!ClientProfileState.investmentDelegated&&ClientProfileState.status.contains("최신"));
                pending(7);acceptStatOperation(original,new DalmaruPayloads.OperationReceipt(8,request,event,"pending_recovery","{\\\"statId\\\":1,\\\"amount\\\":1}",false));require(ClientProfileState.investmentWaiting);
                for(String result:List.of("{}","{\\\"statId\\\":2,\\\"amount\\\":1}","{\\\"statId\\\":1,\\\"amount\\\":1.5}","{\\\"statId\\\":\\\"1\\\",\\\"amount\\\":1}","{\\\"statId\\\":1,\\\"amount\\\":2}")){
                    pending(7);acceptStatOperation(original,new DalmaruPayloads.OperationReceipt(7,request,event,"completed",result,false));require(!ClientProfileState.investmentWaiting&&ClientProfileState.status.contains("확인"));
                }
                pending(7);acceptStatOperation(original,new DalmaruPayloads.OperationReceipt(7,request,new UUID(0,0),"rejected","{}",false));require(!ClientProfileState.investmentWaiting);
                pending(9);ClientProfileState.investmentDelegated=false;ClientProfileState.investmentDeadlineTick=ClientProfileState.tick+1;ClientProfileState.tick();require(!ClientProfileState.investmentWaiting);
            }
        }
        """;
}
