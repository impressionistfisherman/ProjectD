package fixture;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.google.gson.JsonObject;
import java.util.*;
import kr.dalmaru.network.DalmaruNetworking;
import kr.dalmaru.combat.*;
import kr.dalmaru.run.*;
import net.minecraft.server.level.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.*;
import net.minecraft.world.phys.AABB;
public final class QaProbe implements ModInitializer {
 static BlockPos lastFloor;static ServerLevel lastLevel;static int totalRequiredKills,policyKills;
 static java.lang.reflect.Field field(Class<?> c,String n)throws Exception{var f=c.getDeclaredField(n);f.setAccessible(true);return f;}
 static Object value(Object o,String n)throws Exception{return field(o.getClass(),n).get(o);}
 public void onInitialize(){if(!"oscar_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Disposable DB only");CommandRegistrationCallback.EVENT.register((d,a,e)->d.register(Commands.literal("qa16").then(Commands.argument("action",StringArgumentType.word()).then(Commands.argument("player",StringArgumentType.word()).executes(cmd->{try{
 var server=cmd.getSource().getServer();var name=StringArgumentType.getString(cmd,"player");var actor=server.getPlayerList().getPlayerByName(name);if(actor==null||!name.startsWith("Q16"))throw new IllegalArgumentException("Fixture actor required");
 var runtime=(RunRuntime)field(DalmaruNetworking.class,"RUN_RUNTIME").get(null);var combat=(CombatRuntime)field(DalmaruNetworking.class,"COMBAT").get(null);long tick=field(DalmaruNetworking.class,"currentTick").getLong(null);UUID id=runtime.runId(actor);var encounters=(Map<?,?>)field(RunRuntime.class,"encounters").get(runtime);var encounter=id==null?null:encounters.get(id);var action=StringArgumentType.getString(cmd,"action");JsonObject out=new JsonObject();out.addProperty("action",action);out.addProperty("actor",name);out.addProperty("id",id==null?"":id.toString());
 if(encounter!=null){lastFloor=(BlockPos)value(encounter,"floor");lastLevel=(ServerLevel)value(encounter,"level");var mobs=(Map<UUID,BeaconTowerMobs.Kind>)value(encounter,"mobs");var bossId=(UUID)value(encounter,"bossId");var boss=bossId==null?null:(Mob)lastLevel.getEntity(bossId);
 if(action.equals("protect"))for(UUID playerId:runtime.participantIds(id)){var p=server.getPlayerList().getPlayer(playerId);if(p!=null)p.setInvulnerable(true);}
 if(action.equals("clear"))for(UUID mobId:List.copyOf(mobs.keySet())){var m=lastLevel.getEntity(mobId);if(m instanceof LivingEntity living&&living.isAlive()&&CombatRuntime.hurtRpg(living,actor.damageSources().playerAttack(actor),1000000))totalRequiredKills++;}
 if(action.equals("boss")){if(boss==null)throw new IllegalStateException("No boss");CombatRuntime.hurtRpg(boss,actor.damageSources().playerAttack(actor),1000000);}
 if(action.equals("summon")){var commander=mobs.entrySet().stream().filter(entry->entry.getValue()==BeaconTowerMobs.Kind.COMMANDER).findFirst().orElseThrow();var m=(Mob)lastLevel.getEntity(commander.getKey());CombatRuntime.hurtRpg(m,actor.damageSources().playerAttack(actor),CombatUnits.toRpg(m.getMaxHealth())*.55);}
 if(action.equals("policy")){Mob m=EntityType.ZOMBIE.create(lastLevel,EntitySpawnReason.EVENT);if(m==null)throw new IllegalStateException("Policy zombie missing");m.setNoAi(true);m.addTag("dalmaru.mob.fixture");m.setPos(lastFloor.getX()+.5,lastFloor.getY()+1,lastFloor.getZ()+3.5);m.setItemSlot(EquipmentSlot.MAINHAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));m.setGuaranteedDrop(EquipmentSlot.MAINHAND);if(!lastLevel.addFreshEntity(m))throw new IllegalStateException("Policy zombie rejected");CombatRuntime.hurtRpg(m,actor.damageSources().playerAttack(actor),1000000);policyKills++;}
 if(action.equals("immunity")){var target=mobs.keySet().stream().map(lastLevel::getEntity).filter(m->m instanceof Mob&&m.isAlive()).map(m->(Mob)m).findFirst().orElseThrow();actor.setInvulnerable(false);float before=actor.getHealth();CombatRuntime.hurtRpg(actor,actor.damageSources().mobAttack(target),100);out.addProperty("immuneHitBlocked",before==actor.getHealth());out.addProperty("immunity",combat.defenseState(actor.getUUID()).immune(tick));}
 out.addProperty("stage",value(encounter,"stage").toString());out.addProperty("group",(int)value(encounter,"group"));out.addProperty("mobs",mobs.size());out.addProperty("summoned",(boolean)value(encounter,"summoned"));out.addProperty("boss",value(encounter,"bossId")!=null);out.addProperty("revives",runtime.revivesRemaining(actor));if(boss!=null)out.addProperty("bossMaxRpg",CombatUnits.toRpg(boss.getMaxHealth()));
 out.addProperty("x",lastFloor.getX());out.addProperty("y",lastFloor.getY());out.addProperty("z",lastFloor.getZ());out.addProperty("gate27",lastLevel.getBlockState(lastFloor.offset(0,1,27)).isAir());out.addProperty("gate45",lastLevel.getBlockState(lastFloor.offset(0,1,45)).isAir());
 }else out.addProperty("stage","CLOSED");
 if(action.equals("release"))for(var p:server.getPlayerList().getPlayers())if(p.getName().getString().startsWith("Q16"))p.setInvulnerable(false);
 if(lastFloor!=null){var box=new AABB(lastFloor.getX()-9,lastFloor.getY(),lastFloor.getZ()-6,lastFloor.getX()+10,lastFloor.getY()+6,lastFloor.getZ()+75);out.addProperty("loot",lastLevel.getEntitiesOfClass(ItemEntity.class,box).size());out.addProperty("xpOrbs",lastLevel.getEntitiesOfClass(ExperienceOrb.class,box).size());out.addProperty("regionEntities",lastLevel.getEntitiesOfClass(Entity.class,box,entity->!(entity instanceof ServerPlayer)).size());out.addProperty("terrainRestored",!lastLevel.getBlockState(lastFloor).is(net.minecraft.world.level.block.Blocks.DEEPSLATE_TILES));}
 out.addProperty("requiredKills",totalRequiredKills);out.addProperty("policyKills",policyKills);out.addProperty("actorHealth",actor.getHealth());out.addProperty("maxHealth",actor.getMaxHealth());out.addProperty("actorX",actor.getX());out.addProperty("actorZ",actor.getZ());out.addProperty("mode",actor.gameMode.getGameModeForPlayer().getName());var stamina=combat.stamina(actor.getUUID(),tick);if(stamina!=null){out.addProperty("stamina",stamina.current());out.addProperty("maxStamina",stamina.maximum());}System.out.println("QA_SCENE "+out);
 }catch(Exception error){error.printStackTrace();System.out.println("QA_SCENE_ERROR "+error.getMessage());}return 1;})))));}
}
