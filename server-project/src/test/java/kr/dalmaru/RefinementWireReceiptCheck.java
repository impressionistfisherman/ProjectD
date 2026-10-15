package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.Comparator;
import javax.tools.ToolProvider;

public final class RefinementWireReceiptCheck {
    public static void main(String[] args)throws Exception {
        String source=Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruClient.java"));
        int start=source.indexOf("private static void acceptRefinementOperation("),open=source.indexOf('{',start),depth=1,end=open+1;
        if(start<0||open<0)throw new AssertionError("Missing refinement handler");
        while(depth>0){char value=source.charAt(end++);if(value=='{')depth++;if(value=='}')depth--;}
        String code=HARNESS.replace("/* RECEIPT */",source.substring(start,end));
        Path directory=Files.createTempDirectory("refinement-wire-receipt-");
        try {
            Path file=directory.resolve("RefinementWireProof.java");Files.writeString(file,code);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",directory.toString(),file.toString())!=0)throw new AssertionError("Refinement receipt fixture compile failed");
            try(var loader=new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},RefinementWireReceiptCheck.class.getClassLoader())){loader.loadClass("RefinementWireProof").getMethod("run").invoke(null);}
        }finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("Actual refinement receipt replay/reject/malformed result, no original-state application and latest query PASS");
    }
    private static final String HARNESS="""
        import java.util.*;
        class DalmaruPayloads {
            record OperationRequest(long correlationId,UUID requestId,long connectionGeneration,UUID activeCharacterId,String operation,String payload){}
            record OperationReceipt(long correlationId,UUID requestId,UUID eventId,String state,String result,boolean replayed){}
        }
        class ClientEquipmentState {static String status;static int currentGrade=4;static void movementStatus(String message){status=message;}}
        public class RefinementWireProof {
            static int queries;static UUID queried;
            static void requestRefinementState(UUID equipment){queries++;queried=equipment;}
            /* RECEIPT */
            static void check(boolean value){if(!value)throw new AssertionError("Refinement receipt boundary");}
            public static void run(){
                UUID request=UUID.randomUUID(),character=UUID.randomUUID(),event=UUID.randomUUID(),item=UUID.randomUUID();
                var body=new com.google.gson.JsonObject();body.addProperty("equipmentId",item.toString());
                var operation=new DalmaruPayloads.OperationRequest(7,request,1,character,"refine_item",body.toString());
                var result=new com.google.gson.JsonObject();result.addProperty("equipmentId",item.toString());result.addProperty("grade",1);result.addProperty("failures",0);result.addProperty("success",true);
                acceptRefinementOperation(operation,new DalmaruPayloads.OperationReceipt(7,request,event,"pending_recovery",result.toString(),true));
                check(ClientEquipmentState.status.contains("최신")&&ClientEquipmentState.currentGrade==4&&queries==1&&queried.equals(item));
                for(int invalid=0;invalid<6;invalid++){
                    var bad=result.deepCopy();switch(invalid){case 0->bad.remove("grade");case 1->bad.addProperty("grade",1.5);case 2->bad.addProperty("grade",6);case 3->bad.addProperty("failures",-1);case 4->bad.addProperty("success","true");case 5->bad.addProperty("equipmentId",UUID.randomUUID().toString());}
                    acceptRefinementOperation(operation,new DalmaruPayloads.OperationReceipt(7,request,event,"completed",bad.toString(),false));check(ClientEquipmentState.status.contains("확인에 실패")&&ClientEquipmentState.currentGrade==4);
                }
                acceptRefinementOperation(operation,new DalmaruPayloads.OperationReceipt(7,request,new UUID(0,0),"rejected","{}",false));check(ClientEquipmentState.status.contains("동전")&&queries==8&&ClientEquipmentState.currentGrade==4);
            }
        }
        """;
}
