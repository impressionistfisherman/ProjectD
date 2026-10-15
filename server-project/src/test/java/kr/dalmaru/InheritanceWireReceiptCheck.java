package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.Comparator;
import javax.tools.ToolProvider;

public final class InheritanceWireReceiptCheck {
    public static void main(String[] args)throws Exception {
        String source=Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruClient.java"));
        int start=source.indexOf("private static void acceptInheritanceOperation("),open=source.indexOf('{',start),depth=1,end=open+1;
        if(start<0||open<0)throw new AssertionError("Missing inheritance handler");
        while(depth>0){char value=source.charAt(end++);if(value=='{')depth++;if(value=='}')depth--;}
        String code=HARNESS.replace("/* RECEIPT */",source.substring(start,end));
        Path directory=Files.createTempDirectory("inheritance-wire-receipt-");
        try {
            Path file=directory.resolve("InheritanceWireProof.java");Files.writeString(file,code);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",directory.toString(),file.toString())!=0)throw new AssertionError("Inheritance receipt fixture compile failed");
            try(var loader=new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},InheritanceWireReceiptCheck.class.getClassLoader())){loader.loadClass("InheritanceWireProof").getMethod("run").invoke(null);}
        }finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("Actual inheritance receipt replay/reject/malformed result, no original-state application and latest query PASS");
    }
    private static final String HARNESS="""
        import java.util.*;
        class DalmaruPayloads {
            record OperationRequest(long correlationId,UUID requestId,long connectionGeneration,UUID activeCharacterId,String operation,String payload){}
            record OperationReceipt(long correlationId,UUID requestId,UUID eventId,String state,String result,boolean replayed){}
        }
        class ClientEquipmentState {static String status;static int currentCredit=54;static boolean sourcePresent=false;static void movementStatus(String message){status=message;}}
        public class InheritanceWireProof {
            static int queries;
            static void requestEquipment(){queries++;}
            /* RECEIPT */
            static void check(boolean value){if(!value)throw new AssertionError("Inheritance receipt boundary");}
            public static void run(){
                UUID request=UUID.randomUUID(),character=UUID.randomUUID(),event=UUID.randomUUID(),target=UUID.randomUUID(),source=UUID.randomUUID();
                var body=new com.google.gson.JsonObject();body.addProperty("targetEquipmentId",target.toString());body.addProperty("sourceEquipmentId",source.toString());
                var operation=new DalmaruPayloads.OperationRequest(7,request,1,character,"inherit_equipment",body.toString());var result=body.deepCopy();result.addProperty("credit",21);
                acceptInheritanceOperation(operation,new DalmaruPayloads.OperationReceipt(7,request,event,"pending_recovery",result.toString(),true));
                check(ClientEquipmentState.status.contains("최신")&&ClientEquipmentState.currentCredit==54&&!ClientEquipmentState.sourcePresent&&queries==1);
                for(int invalid=0;invalid<6;invalid++){
                    var bad=result.deepCopy();switch(invalid){case 0->bad.remove("credit");case 1->bad.addProperty("credit",1.5);case 2->bad.addProperty("credit",2147483648L);case 3->bad.addProperty("credit",-1);case 4->bad.addProperty("sourceEquipmentId",UUID.randomUUID().toString());case 5->bad.addProperty("targetEquipmentId",UUID.randomUUID().toString());}
                    acceptInheritanceOperation(operation,new DalmaruPayloads.OperationReceipt(7,request,event,"completed",bad.toString(),false));check(ClientEquipmentState.status.contains("확인에 실패")&&ClientEquipmentState.currentCredit==54&&!ClientEquipmentState.sourcePresent);
                }
                acceptInheritanceOperation(operation,new DalmaruPayloads.OperationReceipt(7,request,new UUID(0,0),"rejected","{}",false));check(ClientEquipmentState.status.contains("소유권")&&queries==8);
            }
        }
        """;
}
