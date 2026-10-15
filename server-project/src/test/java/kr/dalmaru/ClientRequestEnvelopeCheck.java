package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import javax.tools.ToolProvider;

public final class ClientRequestEnvelopeCheck {
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("client-request-envelope-");
        try{
            String source=Files.readString(Path.of("src/client/java/kr/dalmaru/client/ClientRequestState.java")).replace("package kr.dalmaru.client;","").replace("import kr.dalmaru.network.DalmaruPayloads;","");
            Path state=dir.resolve("ClientRequestState.java"),fixture=dir.resolve("EnvelopeHarness.java");Files.writeString(state,source);Files.writeString(fixture,HARNESS);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),state.toString(),fixture.toString())!=0)throw new AssertionError("Envelope fixture compile failed");
            try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},ClientRequestEnvelopeCheck.class.getClassLoader())){loader.loadClass("EnvelopeHarness").getMethod("run").invoke(null);}
        }finally{try(var files=Files.walk(dir)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("Actual client UUID/context/exact retry/receipt/selection boundaries PASS");
    }
    private static final String HARNESS="""
        import java.util.*;
        import com.google.gson.JsonObject;
        class DalmaruPayloads{
            record RequestContext(long connectionGeneration,UUID activeCharacterId){}
            record OperationRequest(long correlationId,UUID requestId,long connectionGeneration,UUID activeCharacterId,String operation,String payload){}
            record OperationReceipt(long correlationId,UUID requestId,UUID eventId,String state,String result,boolean replayed){}
        }
        public class EnvelopeHarness{
            static void check(boolean value){if(!value)throw new AssertionError("Client envelope boundary differs");}
            public static void run(){
                UUID a=UUID.randomUUID(),b=UUID.randomUUID();var body=new JsonObject();body.addProperty("equipmentId",UUID.randomUUID().toString());
                ClientRequestState.reset();check(ClientRequestState.begin(1,"enhance_item",body)==null);
                ClientRequestState.selected(a);ClientRequestState.accept(new DalmaruPayloads.RequestContext(1,b));check(ClientRequestState.begin(1,"enhance_item",body)==null);
                ClientRequestState.accept(new DalmaruPayloads.RequestContext(2,a));var first=ClientRequestState.begin(1,"enhance_item",body);check(first.connectionGeneration()==2&&first.activeCharacterId().equals(a));
                String original=first.payload();body.addProperty("changed",true);check(first.payload().equals(original));
                for(int i=0;i<99;i++)check(ClientRequestState.tick().isEmpty());check(ClientRequestState.tick().getFirst()==first);
                check(ClientRequestState.accept(new DalmaruPayloads.OperationReceipt(2,first.requestId(),UUID.randomUUID(),"pending_recovery","{}",true))==null);
                check(ClientRequestState.accept(new DalmaruPayloads.OperationReceipt(1,UUID.randomUUID(),UUID.randomUUID(),"pending_recovery","{}",true))==null);
                check(ClientRequestState.accept(new DalmaruPayloads.OperationReceipt(1,first.requestId(),new UUID(0,0),"pending_recovery","{}",false))==null);
                check(ClientRequestState.accept(new DalmaruPayloads.OperationReceipt(1,first.requestId(),UUID.randomUUID(),"accepted","{}",false))==null);
                for(int i=0;i<99;i++)check(ClientRequestState.tick().isEmpty());check(ClientRequestState.tick().getFirst()==first);
                var receipt=new DalmaruPayloads.OperationReceipt(1,first.requestId(),UUID.randomUUID(),"pending_recovery","{}",true);check(ClientRequestState.accept(receipt)==first&&ClientRequestState.accept(receipt)==null);
                check(ClientRequestState.tick().isEmpty());
                var second=ClientRequestState.begin(2,"enhance_item",body);ClientRequestState.selected(b);check(ClientRequestState.accept(new DalmaruPayloads.OperationReceipt(2,second.requestId(),UUID.randomUUID(),"completed","{}",false))==null);
                ClientRequestState.accept(new DalmaruPayloads.RequestContext(3,b));ClientRequestState.accept(new DalmaruPayloads.RequestContext(2,b));var third=ClientRequestState.begin(3,"enhance_item",body);check(third.connectionGeneration()==3);
                check(ClientRequestState.accept(new DalmaruPayloads.OperationReceipt(3,third.requestId(),new UUID(0,0),"rejected","{}",false))==third);
                ClientRequestState.selected(a);ClientRequestState.accept(new DalmaruPayloads.RequestContext(6,a));var kept=ClientRequestState.begin(9,"enhance_item",body);ClientRequestState.reset();for(int i=0;i<5;i++)check(ClientRequestState.tick().isEmpty());ClientRequestState.selected(a);check(ClientRequestState.tick().isEmpty());ClientRequestState.accept(new DalmaruPayloads.RequestContext(7,a));var resumed=ClientRequestState.tick();check(resumed.size()==1&&resumed.getFirst().requestId().equals(kept.requestId())&&resumed.getFirst().connectionGeneration()==7&&resumed.getFirst().correlationId()==9&&resumed.getFirst().payload().equals(kept.payload()));
                check(ClientRequestState.begin(0,"enhance_item",body)==null);ClientRequestState.reset();for(int i=0;i<101;i++)check(ClientRequestState.tick().isEmpty());
            }
        }
        """;
}
