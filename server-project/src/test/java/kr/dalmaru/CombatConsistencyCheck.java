package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
import kr.dalmaru.combat.CombatRules;
import kr.dalmaru.combat.CombatRewards;
import kr.dalmaru.combat.CombatEffects;
import kr.dalmaru.combat.DefenseState;

public final class CombatConsistencyCheck {
    public static void main(String[]args)throws Exception {
        for(int rank=1;rank<=5;rank++)require(Math.abs(CombatRules.sweepMultiplier(rank)-(1+rank*.1))<1e-9,"C11 rank coefficient");
        require(CombatRewards.excludesRewardTags(java.util.Set.of("dalmaru.mob.guard")),"J02 dungeon rewards allowed");
        require(!CombatRewards.excludesRewardTags(java.util.Set.of("dalmaru.run.other"))&&CombatRewards.experienceForDescriptionId("entity.minecraft.zombie")==25,"Field/boss rewards changed");
        java.util.UUID caster=java.util.UUID.randomUUID(),target=java.util.UUID.randomUUID(),other=java.util.UUID.randomUUID();
        var effects=new CombatEffects();effects.mark(caster,target,5,0);
        require(effects.markBonus(caster,target,159)==.12&&effects.markBonus(other,target,10)==0&&effects.markBonus(caster,target,160)==0,"C12 mark ownership/expiry");
        effects.control(target,"slow",.3,40);effects.control(target,"root",1,20);
        require(effects.slow(target,10)==1&&effects.slow(target,20)==.3,"C14 strongest effect expiry");
        require(effects.cleanseOne(target,21)&&effects.slow(target,21)==0,"C16 cleanse exactly one");
        require(CombatRules.bindingTicks(CombatRules.TargetKind.NORMAL,false,5)==50&&CombatRules.bindingTicks(CombatRules.TargetKind.BOSS,false,5)==20&&CombatRules.bindingSlow(CombatRules.TargetKind.BOSS,true)==.3,"C14 durations/kinds");
        require(effects.shield(target,"A",200,1000,0)==200&&effects.shield(target,"B",200,1000,1)==100,"C13 different-source cap");
        require(effects.absorb(target,250,2)==0&&effects.shieldRemaining(target,2)==50,"C13 expiry ordered absorption");
        require(effects.shield(target,"B",40,1000,3)==0&&effects.shieldRemaining(target,3)==50,"C13 weaker same-source replacement");
        require(effects.shield(target,"B",80,1000,4)==30&&effects.absorb(target,100,5)==20,"C13 stronger replacement/remainder");
        effects.shield(target,"C",80,1000,10);require(effects.shieldRemaining(target,170)==0,"C13 expiration");
        require(CombatRules.critical(.05,.049)&&!CombatRules.critical(.05,.05)&&!CombatRules.critical(1,.6),"C10 critical threshold/cap");
        require(CombatRules.rank(30,130,0)==0&&CombatRules.rank(30,130,5)==130&&CombatRules.rank(.4,2,5)==2,"C09 passive rank endpoints");
        var defense=new DefenseState();require(defense.dodge(0,true)&&defense.charges(0)==1&&defense.immune(3)&&!defense.immune(4),"C07 charge/immunity window");
        require(!defense.dodge(1,true)&&!defense.dodge(9,false)&&defense.dodge(9,true)&&defense.charges(9)==0,"C07 action/ground/second charge");
        require(defense.charges(100)==1&&defense.charges(199)==1&&defense.charges(200)==2,"C07 sequential recharge");
        defense=new DefenseState();defense.guard(true,0);require(defense.reduce(100,0,true,true,true,20,false)==0&&defense.meter(0)==95,"C08 perfect shield/cost");
        require(Math.abs(defense.reduce(100,10,true,true,true,60,false)-30)<1e-9&&defense.meter(10)==35,"C08 reduction/cost");
        require(defense.reduce(100,11,true,false,true,60,false)==100&&defense.meter(11)==35,"C08 rear hit");
        defense.reduce(100,12,true,true,true,60,false);require(defense.staggered(20)&&!defense.guarding(20),"C08 break stagger");
        defense.guard(true,30);require(!defense.guarding(30)&&defense.meter(52)==0&&defense.meter(62)==10,"C08 disabled/recovery delay");
        defense=new DefenseState();defense.revive(10);require(defense.immune(69)&&!defense.immune(70),"C06 revive immunity");
        defense.revive(100);defense.attack();require(!defense.immune(100),"C06 attack ends immunity");
        String source=Files.readString(Path.of("src/main/java/kr/dalmaru/combat/CombatRuntime.java"));
        String methods=method(source,"public void tick(long")+method(source,"public SweepResult useSweep(")+method(source,"public void recordValidHit(");
        String code="""
            import java.util.*;
            public class CombatProof {
                enum SweepResult {UNAVAILABLE,STAMINA,COOLDOWN,USED}
                static class RuntimeState {int maximumStamina=100;double stamina=40,staminaRegenerationPerSecond=5;long lastCombatTick=0;}
                Map<UUID,RuntimeState> states=new HashMap<>();Set<UUID> disconnected=new HashSet<>();Map<UUID,Long>sweepCooldowns=new HashMap<>();
                static final long COMBAT_TIMEOUT_TICKS=200;
                static class Immunity {void attack(){}}
                Immunity defenseState(UUID id){return new Immunity();}
                /* METHODS */
                public static void check(){
                    var runtime=new CombatProof();UUID id=UUID.randomUUID();var state=new RuntimeState();runtime.states.put(id,state);
                    runtime.tick(20);if(state.stamina!=45)throw new AssertionError("C02 combat regeneration missing");
                    runtime.disconnected.add(id);runtime.tick(40);if(state.stamina!=45)throw new AssertionError("Offline regeneration");
                    runtime.disconnected.clear();state.stamina=99;runtime.tick(60);if(state.stamina!=100)throw new AssertionError("C02 regeneration cap");
                    if(runtime.useSweep(id,80)!=SweepResult.USED||state.stamina!=80||state.lastCombatTick!=0)throw new AssertionError("C03 empty cast refreshed combat or lost cost");
                    if(runtime.useSweep(id,81)!=SweepResult.COOLDOWN||state.stamina!=80)throw new AssertionError("Repeated cast charged twice");
                    runtime.recordValidHit(new ServerPlayer(id),82);if(state.lastCombatTick!=82)throw new AssertionError("Valid damage did not refresh");
                }
            }
            class ServerPlayer {UUID id;ServerPlayer(UUID id){this.id=id;}UUID getUUID(){return id;}}
            """.replace("/* METHODS */",methods);
        Path temp=Files.createTempDirectory("dalmaru-combat-check-");
        try{
            Path file=temp.resolve("CombatProof.java");Files.writeString(file,code);
            require(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",temp.toString(),file.toString())==0,"Combat fixture compilation");
            try(var loader=new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()},ClassLoader.getPlatformClassLoader())){loader.loadClass("CombatProof").getMethod("check").invoke(null);}
        }finally{try(var paths=Files.walk(temp)){for(Path file:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);}}
        System.out.println("C02/C03/C09-C14/C16 combat effects and J02 reward gate PASS");
    }
    private static String method(String s,String signature){int start=s.indexOf(signature),open=s.indexOf('{',start),end=open+1,depth=1;for(;depth>0;end++){char c=s.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}return s.substring(start,end);}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
