package kr.dalmaru.run;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/** Write-ahead terrain journal; world changes cannot precede its durable publication. */
public final class RunTerrainRecovery {
    private RunTerrainRecovery() { }
    private static Path directory(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("dalmaru-run-recovery"); }
    static void prepare(ServerLevel level, UUID run, BlockPos origin, Map<BlockPos,BlockState> terrain) {
        var json=new JsonObject();json.addProperty("dimension",level.dimension().identifier().toString());
        json.addProperty("x",origin.getX());json.addProperty("y",origin.getY());json.addProperty("z",origin.getZ());
        var entries=new JsonArray();var ops=level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        terrain.forEach((pos,state)->{var entry=new JsonObject();entry.addProperty("x",pos.getX());entry.addProperty("y",pos.getY());entry.addProperty("z",pos.getZ());entry.add("state",BlockState.CODEC.encodeStart(ops,state).getOrThrow());entries.add(entry);});
        json.add("terrain",entries);
        try {
            Path dir=directory(level.getServer());Files.createDirectories(dir);
            Path pending=dir.resolve(run+".pending"), committed=dir.resolve(run+".json");
            if(Files.exists(committed))throw new IllegalStateException("Unrecovered run region.");
            try(var file=FileChannel.open(pending,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                var bytes=ByteBuffer.wrap(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                while(bytes.hasRemaining())file.write(bytes);file.force(true);
            }
            Files.move(pending,committed,StandardCopyOption.ATOMIC_MOVE);
        } catch(java.io.IOException error){throw new IllegalStateException("Cannot journal run terrain.",error);}
    }
    static void complete(ServerLevel level,UUID run) {
        // Keep the journal if saving fails; the next startup repeats restoration safely.
        level.getChunkSource().save(true);
        try{Files.deleteIfExists(directory(level.getServer()).resolve(run+".json"));}
        catch(java.io.IOException error){throw new IllegalStateException("Cannot acknowledge terrain restoration.",error);}
    }
    public static void recover(MinecraftServer server) {
        Path dir=directory(server);if(!Files.isDirectory(dir))return;
        try(var paths=Files.list(dir)) {
            for(Path file:paths.filter(path->path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                UUID run=UUID.fromString(file.getFileName().toString().replace(".json",""));
                var json=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                ServerLevel level=server.getLevel(ResourceKey.create(Registries.DIMENSION,Identifier.parse(json.get("dimension").getAsString())));
                if(level==null)throw new IllegalStateException("Run recovery dimension unavailable.");
                int x=json.get("x").getAsInt(),y=json.get("y").getAsInt(),z=json.get("z").getAsInt();
                var terrain=new HashMap<BlockPos,BlockState>();var ops=level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                for(var value:json.getAsJsonArray("terrain")) {
                    var entry=value.getAsJsonObject();var pos=new BlockPos(entry.get("x").getAsInt(),entry.get("y").getAsInt(),entry.get("z").getAsInt());
                    if(pos.getX()<x-9||pos.getX()>x+9||pos.getY()<y||pos.getY()>y+5||pos.getZ()<z-6||pos.getZ()>z+74)throw new IllegalStateException("Invalid recovery terrain bounds.");
                    if(terrain.put(pos,BlockState.CODEC.parse(ops,entry.get("state")).getOrThrow())!=null)throw new IllegalStateException("Duplicate recovery block.");
                }
                if(terrain.size()!=19*81*6)throw new IllegalStateException("Incomplete recovery terrain.");
                BeaconTowerMap.restore(level,terrain);
                for(Entity entity:level.getEntitiesOfClass(Entity.class,new AABB(x-9,y,z-6,x+10,y+6,z+75),entity->!(entity instanceof net.minecraft.server.level.ServerPlayer)))entity.discard();
                complete(level,run);
            }
        }catch(java.io.IOException error){throw new IllegalStateException("Cannot recover run terrain.",error);}
    }
}
