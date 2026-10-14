package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import javax.tools.ToolProvider;

public final class ClientReplyRecoveryCheck {
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("client-reply-recovery-");
        try{
            var compilerArgs=new ArrayList<String>(List.of("-d",dir.toString()));
            for(String name:List.of("ClientLoadoutState","ClientSkillState")){
                String source=Files.readString(Path.of("src/client/java/kr/dalmaru/client/"+name+".java")).replace("package kr.dalmaru.client;","").replace("import kr.dalmaru.network.DalmaruPayloads;","");
                Path file=dir.resolve(name+".java");Files.writeString(file,source);compilerArgs.add(file.toString());
            }
            Path file=dir.resolve("ReplyHarness.java");Files.writeString(file,HARNESS);compilerArgs.add(file.toString());
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,compilerArgs.toArray(String[]::new))!=0)throw new AssertionError("Client reply fixture compile failed");
            try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},ClassLoader.getPlatformClassLoader())){loader.loadClass("ReplyHarness").getMethod("run").invoke(null);}
        }finally{try(var files=Files.walk(dir)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("Actual client classes: lost/reordered/foreign-character/loadout-recovery/rank-retry PASS");
    }
    private static final String HARNESS="""
        import java.util.*;
        class DalmaruPayloads{
            record LoadoutResponse(long requestId,UUID characterId,long revision,List<String> slots,boolean editable,String status){}
            record SkillRank(String skillId,int rank){}
            record SkillRanksResponse(long requestId,List<SkillRank> ranks){}
        }
        public class ReplyHarness{
            static void check(boolean value){if(!value)throw new AssertionError("Client reply recovery differs");}
            static DalmaruPayloads.LoadoutResponse load(long request,UUID character,long revision,String status){return new DalmaruPayloads.LoadoutResponse(request,character,revision,List.of("","","","",""),true,status);}
            static void deadline(String name)throws Exception{var field=ClientLoadoutState.class.getDeclaredField(name);field.setAccessible(true);field.setLong(null,System.nanoTime()-1);}
            public static void run()throws Exception{
                UUID a=UUID.randomUUID(),b=UUID.randomUUID();ClientLoadoutState.reset();ClientLoadoutState.selected(a);
                long first=ClientLoadoutState.next();ClientLoadoutState.queried(first);deadline("queryUntil");check(ClientLoadoutState.tick());
                long second=ClientLoadoutState.next();ClientLoadoutState.queried(second);ClientLoadoutState.accept(load(second,a,0,"new"));ClientLoadoutState.accept(load(first,a,0,"old"));check(ClientLoadoutState.current().status().equals("new"));
                long mutation=ClientLoadoutState.next();ClientLoadoutState.pending(mutation);
                long query=ClientLoadoutState.next();ClientLoadoutState.queried(query);ClientLoadoutState.accept(load(query,a,0,"snapshot"));check(ClientLoadoutState.waiting());
                deadline("pendingUntil");check(ClientLoadoutState.waiting());check(ClientLoadoutState.tick());
                long recovery=ClientLoadoutState.next();ClientLoadoutState.queried(recovery);ClientLoadoutState.accept(load(recovery,b,1,"foreign"));check(ClientLoadoutState.waiting());
                ClientLoadoutState.accept(load(recovery,a,1,"recovered"));check(!ClientLoadoutState.waiting());
                ClientLoadoutState.accept(load(mutation,a,0,"old revision"));check(ClientLoadoutState.current().revision()==1);
                ClientLoadoutState.accept(load(mutation,a,2,"late committed"));check(ClientLoadoutState.current().revision()==2);
                long old=ClientLoadoutState.next();ClientLoadoutState.selected(b);ClientLoadoutState.accept(load(old,b,10,"previous selection"));check(ClientLoadoutState.current()==null);
                long current=ClientLoadoutState.next();ClientLoadoutState.queried(current);ClientLoadoutState.accept(load(current,b,0,"selected"));check(ClientLoadoutState.current().characterId().equals(b));
                ClientSkillState.resetForConnection();long rank1=ClientSkillState.nextRequestId(),rank2=ClientSkillState.nextRequestId();
                ClientSkillState.accept(new DalmaruPayloads.SkillRanksResponse(rank2,List.of(new DalmaruPayloads.SkillRank("s",2))));ClientSkillState.accept(new DalmaruPayloads.SkillRanksResponse(rank1,List.of(new DalmaruPayloads.SkillRank("s",1))));check(ClientSkillState.rank("s")==2);
                long stale=ClientSkillState.nextRequestId();ClientSkillState.selected();ClientSkillState.accept(new DalmaruPayloads.SkillRanksResponse(stale,List.of(new DalmaruPayloads.SkillRank("s",3))));check(ClientSkillState.rank("s")==0);
                long pending=ClientSkillState.nextRequestId();for(int i=0;i<99;i++)check(!ClientSkillState.tick());check(ClientSkillState.tick());
                long retry=ClientSkillState.nextRequestId();ClientSkillState.accept(new DalmaruPayloads.SkillRanksResponse(pending,List.of(new DalmaruPayloads.SkillRank("s",4))));check(ClientSkillState.rank("s")==0);
                ClientSkillState.accept(new DalmaruPayloads.SkillRanksResponse(retry,List.of(new DalmaruPayloads.SkillRank("s",5))));check(ClientSkillState.rank("s")==5&&!ClientSkillState.tick());
            }
        }
        """;
}
