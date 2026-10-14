package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** Exercises the actual receipt parser and pending-skill completion without a game client. */
public final class SkillWireReceiptCheck {
    public static void main(String[] args)throws Exception {
        String client=Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruClient.java"));
        String profile=Files.readString(Path.of("src/client/java/kr/dalmaru/client/ClientProfileState.java"));
        String code=HARNESS.replace("/* RECEIPT */",method(client,"private static void acceptSkillOperation(")).replace("/* COMPLETE */",method(profile,"static void completeSkill("));
        Path directory=Files.createTempDirectory("skill-wire-receipt-");
        try {
            Path file=directory.resolve("SkillWireProof.java");Files.writeString(file,code);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",directory.toString(),file.toString())!=0)throw new AssertionError("Receipt fixture compilation");
            try(var loader=new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},SkillWireReceiptCheck.class.getClassLoader())){loader.loadClass("SkillWireProof").getMethod("run").invoke(null);}
        }finally{try(var paths=Files.walk(directory)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
        System.out.println("Actual skill receipt replay/reject/malformed/correlation and latest-state query boundaries PASS");
    }
    private static String method(String source,String signature){int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,end=open+1;if(start<0||open<0)throw new AssertionError("Method not found");while(depth>0){char x=source.charAt(end++);if(x=='{')depth++;if(x=='}')depth--;}return source.substring(start,end);}
    private static final String HARNESS="""
        import java.util.*;
        class DalmaruPayloads {
            record OperationRequest(long correlationId,UUID requestId,long connectionGeneration,UUID activeCharacterId,String operation,String payload){}
            record OperationReceipt(long correlationId,UUID requestId,UUID eventId,String state,String result,boolean replayed){}
        }
        class ClientProfileState {
            static boolean skillWaiting;static long currentSkillRequestId;static Object skillRequest;static String status;
            /* COMPLETE */
        }
        public class SkillWireProof {
            static int rankQueries,loadoutQueries;
            static void requestSkillRanks(){rankQueries++;}static void requestLoadout(){loadoutQueries++;}
            /* RECEIPT */
            static void require(boolean value){if(!value)throw new AssertionError("Skill receipt boundary");}
            static void pending(long id){ClientProfileState.skillWaiting=true;ClientProfileState.currentSkillRequestId=id;ClientProfileState.skillRequest=new Object();}
            public static void run(){
                UUID request=UUID.randomUUID(),character=UUID.randomUUID(),event=UUID.randomUUID();
                var original=new DalmaruPayloads.OperationRequest(7,request,1,character,"learn_skill","{\\"skillId\\":\\"skill.warrior.sweep\\"}");
                pending(7);acceptSkillOperation(original,new DalmaruPayloads.OperationReceipt(7,request,event,"pending_recovery","{\\"skillId\\":\\"skill.warrior.sweep\\",\\"rank\\":1}",true));
                require(!ClientProfileState.skillWaiting&&ClientProfileState.skillRequest==null&&ClientProfileState.status.contains("최신")&&rankQueries==1&&loadoutQueries==1);
                pending(7);acceptSkillOperation(original,new DalmaruPayloads.OperationReceipt(8,request,event,"pending_recovery","{\\"skillId\\":\\"skill.warrior.sweep\\",\\"rank\\":2}",false));require(ClientProfileState.skillWaiting);
                for(String result:List.of("{}","{\\"skillId\\":\\"foreign\\",\\"rank\\":1}","{\\"skillId\\":\\"skill.warrior.sweep\\",\\"rank\\":1.5}","{\\"skillId\\":\\"skill.warrior.sweep\\",\\"rank\\":6}")){
                    pending(7);acceptSkillOperation(original,new DalmaruPayloads.OperationReceipt(7,request,event,"completed",result,false));require(!ClientProfileState.skillWaiting&&ClientProfileState.status.contains("확인"));
                }
                pending(7);acceptSkillOperation(original,new DalmaruPayloads.OperationReceipt(7,request,new UUID(0,0),"rejected","{}",false));require(!ClientProfileState.skillWaiting&&rankQueries==7&&loadoutQueries==7);
            }
        }
        """;
}
