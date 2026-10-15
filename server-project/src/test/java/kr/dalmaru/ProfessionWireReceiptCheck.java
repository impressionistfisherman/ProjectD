package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** Exercises the actual profession receipt parser and retry completion without a game client. */
public final class ProfessionWireReceiptCheck {
    public static void main(String[] args)throws Exception {
        String client=Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruClient.java"));
        String profile=Files.readString(Path.of("src/client/java/kr/dalmaru/client/ClientProfileState.java"));
        String code=HARNESS.replace("/* RECEIPT */",method(client,"private static void acceptProfessionOperation(")).replace("/* COMPLETE */",method(profile,"static void completeProfession(")+method(profile,"static void delegateProfessionRetry(")+method(profile,"static DalmaruPayloads.CharacterProfessionRequest retryProfession("));
        Path directory=Files.createTempDirectory("profession-wire-receipt-");
        try {
            Path file=directory.resolve("ProfessionWireProof.java");Files.writeString(file,code);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",directory.toString(),file.toString())!=0)throw new AssertionError("Receipt fixture compilation");
            try(var loader=new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},ProfessionWireReceiptCheck.class.getClassLoader())){loader.loadClass("ProfessionWireProof").getMethod("run").invoke(null);}
        }finally{try(var paths=Files.walk(directory)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
        System.out.println("Actual profession receipt replay/reject/malformed/correlation and exclusive retry PASS");
    }
    private static String method(String source,String signature){int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,end=open+1;if(start<0||open<0)throw new AssertionError("Method not found");while(depth>0){char x=source.charAt(end++);if(x=='{')depth++;if(x=='}')depth--;}return source.substring(start,end);}
    private static final String HARNESS="""
        import java.util.*;
        class DalmaruPayloads {
            record OperationRequest(long correlationId,UUID requestId,long connectionGeneration,UUID activeCharacterId,String operation,String payload){}
            record OperationReceipt(long correlationId,UUID requestId,UUID eventId,String state,String result,boolean replayed){}
            record CharacterProfessionRequest(long requestId,String profession){}
        }
        class ClientProfileState {
            static boolean professionWaiting;static long currentProfessionRequestId;static int tick,professionDeadline;static String status;
            static DalmaruPayloads.CharacterProfessionRequest professionRequest;
            /* COMPLETE */
        }
        public class ProfessionWireProof {
            static int rankQueries,loadoutQueries;
            static void requestSkillRanks(){rankQueries++;}static void requestLoadout(){loadoutQueries++;}
            /* RECEIPT */
            static void require(boolean value){if(!value)throw new AssertionError("Profession receipt boundary");}
            static void pending(long id){ClientProfileState.professionWaiting=true;ClientProfileState.currentProfessionRequestId=id;ClientProfileState.professionRequest=new DalmaruPayloads.CharacterProfessionRequest(id,"WARRIOR");}
            public static void run(){
                UUID request=UUID.randomUUID(),character=UUID.randomUUID(),event=UUID.randomUUID();
                var original=new DalmaruPayloads.OperationRequest(7,request,1,character,"select_profession","{\\\"profession\\\":\\\"WARRIOR\\\"}");
                String good="{\\\"profession\\\":\\\"WARRIOR\\\"}";
                pending(7);require(ClientProfileState.retryProfession()!=null);ClientProfileState.delegateProfessionRetry();ClientProfileState.tick+=201;require(ClientProfileState.retryProfession()==null&&ClientProfileState.professionWaiting);
                acceptProfessionOperation(original,new DalmaruPayloads.OperationReceipt(7,request,event,"pending_recovery",good,true));require(!ClientProfileState.professionWaiting&&ClientProfileState.professionRequest==null&&ClientProfileState.status.contains("최신")&&rankQueries==1&&loadoutQueries==1);
                pending(7);acceptProfessionOperation(original,new DalmaruPayloads.OperationReceipt(8,request,event,"pending_recovery",good,false));require(ClientProfileState.professionWaiting);
                for(String result:List.of("{}","{\\\"profession\\\":\\\"MAGE\\\"}","{\\\"profession\\\":1}")){
                    pending(7);acceptProfessionOperation(original,new DalmaruPayloads.OperationReceipt(7,request,event,"completed",result,false));require(!ClientProfileState.professionWaiting&&ClientProfileState.status.contains("확인"));
                }
                pending(7);acceptProfessionOperation(original,new DalmaruPayloads.OperationReceipt(7,request,new UUID(0,0),"rejected","{}",false));require(!ClientProfileState.professionWaiting);
            }
        }
        """;
}
