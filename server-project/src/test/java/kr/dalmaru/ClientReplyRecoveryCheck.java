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
            for(String name:List.of("ClientLoadoutState","ClientSkillState","ClientProfileState","ClientEquipmentState")){
                String source=Files.readString(Path.of("src/client/java/kr/dalmaru/client/"+name+".java")).replace("package kr.dalmaru.client;","").replace("import kr.dalmaru.network.DalmaruPayloads;","");
                Path file=dir.resolve(name+".java");Files.writeString(file,source);compilerArgs.add(file.toString());
            }
            String client=Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruClient.java"));
            Path file=dir.resolve("ReplyHarness.java");Files.writeString(file,HARNESS.replace("/* MOVE RECEIPT */",method(client,"private static void acceptEquipmentMoveOperation(")));compilerArgs.add(file.toString());
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,compilerArgs.toArray(String[]::new))!=0)throw new AssertionError("Client reply fixture compile failed");
            try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},ClassLoader.getPlatformClassLoader())){loader.loadClass("ReplyHarness").getMethod("run").invoke(null);}
        }finally{try(var files=Files.walk(dir)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("Actual client classes: lost/reordered/foreign-character/loadout-recovery/rank-retry PASS");
    }
    private static String method(String source,String signature){
        int start=source.indexOf(signature);if(start<0)throw new AssertionError("Missing receipt handler");
        int open=source.indexOf('{',start),depth=1,end=open+1;
        while(depth>0){char value=source.charAt(end++);if(value=='{')depth++;if(value=='}')depth--;}
        return source.substring(start,end);
    }
    private static final String HARNESS="""
        import java.util.*;
        class DalmaruPayloads{
            record RefinementStateResponse(long requestId,UUID characterId,UUID equipmentId,int grade,int failures){}

            record OperationReceipt(long correlationId,UUID requestId,UUID eventId,String state,String result,boolean replayed){}
            record LoadoutResponse(long requestId,UUID characterId,long revision,List<String> slots,boolean editable,String status){}
            record SkillRank(String skillId,int rank){}
            record SkillRanksResponse(long requestId,List<SkillRank> ranks){}
            record ProfileData(int unspentStatPoints,int unspentSkillPoints,boolean freeResetAvailable,String profession){}
            record CharacterProfileResponse(UUID characterId,ProfileData profile){}
            record CharacterStatResetRequest(long requestId,UUID characterId){}
            record CharacterProfessionRequest(long requestId,String profession){}
            record SkillLearnRequest(long requestId,String skillId){}
            record CharacterStatInvestmentResponse(long requestId,UUID characterId,boolean accepted,String status,ProfileData profile){}
            record CharacterStatResetResponse(long requestId,UUID characterId,boolean accepted,String status,ProfileData profile){}
            record CharacterProfessionResponse(long requestId,boolean accepted,String status,ProfileData profile){}
            record SkillLearnResponse(long requestId,boolean accepted,String status,ProfileData profile){}
            record EquipmentData(UUID equipmentId,int slotId,String itemId,int enhancement,int consecutiveFailures,boolean bound){}
            record EquipmentListResponse(long requestId,String status,List<EquipmentData> equipment){}
            record EquipmentEnhanceResponse(long requestId,boolean accepted,boolean success,int enhancement,int consecutiveFailures,String status){}
        }
        public class ReplyHarness{
            static int equipmentQueries;
            static void requestEquipment(){equipmentQueries++;}
            /* MOVE RECEIPT */
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
                long common=ClientLoadoutState.next();ClientLoadoutState.pending(common);ClientLoadoutState.delegateRetry();deadline("pendingUntil");check(!ClientLoadoutState.tick()&&ClientLoadoutState.waiting());
                ClientLoadoutState.completeOperation(common+1);check(ClientLoadoutState.waiting());ClientLoadoutState.completeOperation(common);check(!ClientLoadoutState.waiting()&&ClientLoadoutState.current().revision()==0);
                ClientSkillState.resetForConnection();long rank1=ClientSkillState.nextRequestId(),rank2=ClientSkillState.nextRequestId();
                ClientSkillState.accept(new DalmaruPayloads.SkillRanksResponse(rank2,List.of(new DalmaruPayloads.SkillRank("s",2))));ClientSkillState.accept(new DalmaruPayloads.SkillRanksResponse(rank1,List.of(new DalmaruPayloads.SkillRank("s",1))));check(ClientSkillState.rank("s")==2);
                long stale=ClientSkillState.nextRequestId();ClientSkillState.selected();ClientSkillState.accept(new DalmaruPayloads.SkillRanksResponse(stale,List.of(new DalmaruPayloads.SkillRank("s",3))));check(ClientSkillState.rank("s")==0);
                long pending=ClientSkillState.nextRequestId();for(int i=0;i<99;i++)check(!ClientSkillState.tick());check(ClientSkillState.tick());
                long retry=ClientSkillState.nextRequestId();ClientSkillState.accept(new DalmaruPayloads.SkillRanksResponse(pending,List.of(new DalmaruPayloads.SkillRank("s",4))));check(ClientSkillState.rank("s")==0);
                ClientSkillState.accept(new DalmaruPayloads.SkillRanksResponse(retry,List.of(new DalmaruPayloads.SkillRank("s",5))));check(ClientSkillState.rank("s")==5&&!ClientSkillState.tick());
                ClientProfileState.resetForConnection();ClientProfileState.selected(a);
                var profile=new DalmaruPayloads.ProfileData(5,2,true,"WARRIOR");
                ClientProfileState.accept(new DalmaruPayloads.CharacterProfileResponse(a,profile));
                check(ClientProfileState.canLearn()&&ClientProfileState.beginSkill("s"));check(!ClientProfileState.canLearn()&&!ClientProfileState.beginSkill("other"));
                long skill=ClientProfileState.currentSkillRequestId();for(int i=0;i<99;i++){ClientProfileState.tick();check(ClientProfileState.retrySkill()==null);}
                ClientProfileState.tick();var skillRetry=ClientProfileState.retrySkill();check(skillRetry.requestId()==skill&&skillRetry.skillId().equals("s"));
                for(int i=0;i<100;i++)ClientProfileState.tick();check(ClientProfileState.retrySkill()==skillRetry);
                ClientProfileState.acceptSkill(new DalmaruPayloads.SkillLearnResponse(skill+1,true,"wrong",profile));check(!ClientProfileState.canLearn());
                ClientProfileState.acceptSkill(new DalmaruPayloads.SkillLearnResponse(skill,true,"done",new DalmaruPayloads.ProfileData(5,1,true,"WARRIOR")));check(ClientProfileState.canLearn()&&ClientProfileState.retrySkill()==null);
                check(ClientProfileState.beginSkill("s2"));long skill2=ClientProfileState.currentSkillRequestId();ClientProfileState.selected(b);
                ClientProfileState.acceptSkill(new DalmaruPayloads.SkillLearnResponse(skill2,true,"old character",profile));ClientProfileState.accept(new DalmaruPayloads.CharacterProfileResponse(a,profile));check(ClientProfileState.current()==null&&ClientProfileState.retrySkill()==null);
                ClientProfileState.accept(new DalmaruPayloads.CharacterProfileResponse(b,profile));check(ClientProfileState.current().characterId().equals(b));
                check(ClientProfileState.beginReset());long reset=ClientProfileState.currentResetRequestId();for(int i=0;i<100;i++)ClientProfileState.tick();var resetRetry=ClientProfileState.retryReset();check(resetRetry.requestId()==reset&&resetRetry.characterId().equals(b));
                for(int i=0;i<100;i++)ClientProfileState.tick();check(ClientProfileState.retryReset()==resetRetry);
                ClientProfileState.acceptReset(new DalmaruPayloads.CharacterStatResetResponse(reset,b,true,"reset",new DalmaruPayloads.ProfileData(5,2,false,"WARRIOR")));check(!ClientProfileState.canReset()&&ClientProfileState.retryReset()==null);
                ClientProfileState.accept(new DalmaruPayloads.CharacterProfileResponse(b,new DalmaruPayloads.ProfileData(5,2,false,"")));
                check(ClientProfileState.beginProfession("WARRIOR")&&!ClientProfileState.beginProfession("MAGE"));long profession=ClientProfileState.currentProfessionRequestId();for(int i=0;i<100;i++)ClientProfileState.tick();var professionRetry=ClientProfileState.retryProfession();check(professionRetry.requestId()==profession&&professionRetry.profession().equals("WARRIOR"));
                ClientProfileState.acceptProfession(new DalmaruPayloads.CharacterProfessionResponse(profession,true,"profession",profile));check(ClientProfileState.retryProfession()==null&&!ClientProfileState.beginProfession("MAGE"));
                check(ClientProfileState.beginSkill("uuid-skill"));long delegated=ClientProfileState.currentSkillRequestId();ClientProfileState.delegateSkillRetry();for(int i=0;i<100;i++)ClientProfileState.tick();check(ClientProfileState.retrySkill()==null&&!ClientProfileState.canLearn());ClientProfileState.completeSkill(delegated+1,"wrong");check(!ClientProfileState.canLearn());ClientProfileState.completeSkill(delegated,"done");check(ClientProfileState.canLearn());
                ClientProfileState.accept(new DalmaruPayloads.CharacterProfileResponse(b,new DalmaruPayloads.ProfileData(5,0,false,"WARRIOR")));check(!ClientProfileState.canLearn()&&!ClientProfileState.beginSkill("s"));
                ClientEquipmentState.resetForConnection();UUID gear=UUID.randomUUID();
                long inventory1=ClientEquipmentState.nextRequestId(),inventory2=ClientEquipmentState.nextRequestId();ClientEquipmentState.queried(inventory2);
                var latestItem=new DalmaruPayloads.EquipmentData(gear,0,"item",2,0,true);
                ClientEquipmentState.accept(new DalmaruPayloads.EquipmentListResponse(inventory2,"latest",List.of(latestItem)));
                ClientEquipmentState.accept(new DalmaruPayloads.EquipmentListResponse(inventory1,"old",List.of(new DalmaruPayloads.EquipmentData(gear,0,"item",1,0,true))));check(ClientEquipmentState.equipment().getFirst().enhancement()==2);
                ClientEquipmentState.accept(new DalmaruPayloads.EquipmentEnhanceResponse(3,true,true,1,0,"already processed"));check(ClientEquipmentState.equipment().getFirst().enhancement()==2&&ClientEquipmentState.status().equals("already processed"));
                ClientEquipmentState.accept(new DalmaruPayloads.EquipmentListResponse(inventory2,"latest",List.of(latestItem)));check(ClientEquipmentState.status().equals("already processed"));
                var expiry=ClientEquipmentState.class.getDeclaredField("statusUntil");expiry.setAccessible(true);expiry.setLong(null,System.nanoTime()-1);check(ClientEquipmentState.status().equals("latest"));
                acceptEquipmentMoveOperation(new DalmaruPayloads.OperationReceipt(90,UUID.randomUUID(),UUID.randomUUID(),"completed","{}",true));check(equipmentQueries==1&&ClientEquipmentState.equipment().equals(List.of(latestItem))&&ClientEquipmentState.status().contains("최신"));
                acceptEquipmentMoveOperation(new DalmaruPayloads.OperationReceipt(91,UUID.randomUUID(),new UUID(0,0),"rejected","{}",false));check(equipmentQueries==2&&ClientEquipmentState.equipment().equals(List.of(latestItem))&&ClientEquipmentState.status().contains("전투"));
                long refineQuery=ClientEquipmentState.nextRequestId();ClientEquipmentState.queriedRefinement(refineQuery,gear);
                ClientEquipmentState.acceptRefinement(new DalmaruPayloads.RefinementStateResponse(refineQuery,a,gear,2,0));check(ClientEquipmentState.refinement(gear).contains("재련 2"));
                ClientEquipmentState.acceptRefinement(new DalmaruPayloads.RefinementStateResponse(refineQuery-1,a,gear,1,9));check(ClientEquipmentState.refinement(gear).contains("재련 2"));
                ClientEquipmentState.acceptRefinement(new DalmaruPayloads.RefinementStateResponse(refineQuery+1,a,gear,6,0));check(ClientEquipmentState.refinement(gear).contains("재련 2"));
                ClientEquipmentState.acceptRefinement(new DalmaruPayloads.RefinementStateResponse(refineQuery+1,a,UUID.randomUUID(),1,0));
                long oldInventory=ClientEquipmentState.nextRequestId();ClientEquipmentState.selected();ClientEquipmentState.accept(new DalmaruPayloads.EquipmentListResponse(oldInventory,"previous",List.of(latestItem)));check(ClientEquipmentState.equipment().isEmpty());check(ClientEquipmentState.refinement(gear).isEmpty());
            }
        }
        """;
}
