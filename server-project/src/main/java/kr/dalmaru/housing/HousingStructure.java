package kr.dalmaru.housing;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Temporary home shell. The same parcel is not built twice. */
public final class HousingStructure {
	public static final int SPACING = 256;

	private HousingStructure() { }

	public static int originX(int parcel) { return parcel * SPACING; }

	public static Map<BlockPos, String> plan(int originX, int originZ) {
		var blocks = new LinkedHashMap<BlockPos, String>();
		for (int x = 0; x <= 95; x++) for (int z = 0; z <= 47; z++) put(blocks, originX + x, 63, originZ + z, "minecraft:stone");
		for (int x = 2; x <= 7; x++) for (int z = 2; z <= 7; z++) for (int y = 64; y <= 66; y++) {
			boolean edge = x == 2 || x == 7 || z == 2 || z == 7;
			boolean door = x == 4 && z == 2 && y <= 65;
			put(blocks, originX + x, y, originZ + z, edge && !door ? "minecraft:oak_planks" : "minecraft:air");
		}
		for (int plot = 0; plot < 4; plot++) for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) put(blocks, originX + 32 + plot * 16 + x, 63, originZ + z, "minecraft:farmland");
		put(blocks, originX + 2, 64, originZ + 34, "minecraft:crafting_table");
		return blocks;
	}

	public static void place(ServerLevel level, int originX, int originZ) {
		plan(originX, originZ).forEach((pos, block) -> level.setBlock(pos, state(block), 3));
	}

	private static void put(Map<BlockPos, String> blocks, int x, int y, int z, String block) {
		blocks.put(new BlockPos(x, y, z), block);
	}

	private static BlockState state(String block) {
		return switch (block) {
			case "minecraft:stone" -> Blocks.STONE.defaultBlockState();
			case "minecraft:oak_planks" -> Blocks.OAK_PLANKS.defaultBlockState();
			case "minecraft:farmland" -> Blocks.FARMLAND.defaultBlockState();
			case "minecraft:crafting_table" -> Blocks.CRAFTING_TABLE.defaultBlockState();
			case "minecraft:air" -> Blocks.AIR.defaultBlockState();
			default -> throw new IllegalArgumentException("Unknown home block.");
		};
	}
}
