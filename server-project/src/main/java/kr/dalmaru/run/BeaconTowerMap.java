package kr.dalmaru.run;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

final class BeaconTowerMap {
    private BeaconTowerMap() { }

    static java.util.Map<BlockPos,net.minecraft.world.level.block.state.BlockState> capture(ServerLevel level,BlockPos origin){
        var result=new java.util.HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(int x=-9;x<=9;x++)for(int z=-6;z<=74;z++)for(int y=0;y<=5;y++){BlockPos pos=origin.offset(x,y,z);if(level.getBlockEntity(pos)!=null)throw new IllegalStateException("Occupied block entity in run region.");result.put(pos,level.getBlockState(pos));}
        return result;
    }
    static void restore(ServerLevel level,java.util.Map<BlockPos,net.minecraft.world.level.block.state.BlockState> terrain){
        if(terrain!=null)terrain.forEach((pos,state)->level.setBlock(pos,state,3));
    }
    static void build(ServerLevel level, BlockPos origin) {
        for (int x = -8; x <= 8; x++) for (int z = -6; z <= 74; z++) for (int y = 1; y <= 4; y++) {
            level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        for (int x = -9; x <= 9; x++) for (int z = -6; z <= 74; z++) level.setBlock(origin.offset(x, 0, z), Blocks.DEEPSLATE_TILES.defaultBlockState(), 3);
        for (int z = -6; z <= 74; z++) for (int y = 1; y <= 4; y++) {
            level.setBlock(origin.offset(-9, y, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 3);
            level.setBlock(origin.offset(9, y, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 3);
        }
        for(int x=-9;x<=9;x++)for(int z=-6;z<=74;z++)level.setBlock(origin.offset(x,5,z),Blocks.DEEPSLATE_BRICKS.defaultBlockState(),3);
        for(int x=-9;x<=9;x++)for(int y=1;y<=4;y++)for(int z:new int[]{-6,74})level.setBlock(origin.offset(x,y,z),Blocks.DEEPSLATE_BRICKS.defaultBlockState(),3);
        for (int gate : new int[] {27, 45, 64}) close(level, origin, gate);
        for (int x : new int[] {-6, 6}) for (int z : new int[] {9, 21, 48, 60}) for (int y = 1; y <= 3; y++) level.setBlock(origin.offset(x, y, z), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 3);
    }

    static void open(ServerLevel level, BlockPos origin, int z) {
        for (int x = -2; x <= 2; x++) for (int y = 1; y <= 3; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
    }

    private static void close(ServerLevel level, BlockPos origin, int z) {
        for (int x = -2; x <= 2; x++) for (int y = 1; y <= 3; y++) level.setBlock(origin.offset(x, y, z), Blocks.IRON_BARS.defaultBlockState(), 3);
    }
}
