package kr.dalmaru;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

/** Village layout is fixed data: same offsets for every seed and every village. */
public final class P6VillageLayoutCheck {
    public static void main(String[] args) throws Exception {
        var layout = JsonParser.parseString(Files.readString(Path.of("src/main/resources/content/village-layout.json"))).getAsJsonObject();
        var villages = JsonParser.parseString(Files.readString(Path.of("src/main/resources/content/villages.json"))).getAsJsonObject();
        if (layout.has("seed")) throw new AssertionError("Layout must not depend on a world seed");
        if (layout.get("radius").getAsInt() != 8) throw new AssertionError("Layout radius drifted");
        var expected = java.util.Map.of("waypoint", "\uC5ED\uCC38", "blacksmith", "\uB300\uC7A5\uAC04", "shop", "\uC0C1\uC810", "housing", "\uAC70\uCC98 \uC785\uAD6C");
        var seen = new HashSet<String>();
        var cells = new HashSet<String>();
        for (var entry : layout.getAsJsonArray("buildings")) {
            var building = entry.getAsJsonObject();
            String id = building.get("id").getAsString();
            if (!expected.getOrDefault(id, "").equals(building.get("name").getAsString())) throw new AssertionError("Unexpected building: " + id);
            if (!seen.add(id)) throw new AssertionError("Duplicate building: " + id);
            int x0 = building.get("x").getAsInt(), z0 = building.get("z").getAsInt(), w = building.get("w").getAsInt(), d = building.get("d").getAsInt();
            for (int x = x0; x < x0 + w; x++) for (int z = z0; z < z0 + d; z++) {
                int ring = Math.max(Math.abs(x), Math.abs(z));
                if (ring <= 4 || ring >= 8 || x == 0 || z == 0) throw new AssertionError("Building overlaps the stair well, road, or shell: " + id);
                if (!cells.add(x + ":" + z)) throw new AssertionError("Buildings overlap");
            }
        }
        if (!seen.equals(expected.keySet())) throw new AssertionError("Village is missing a building");
        var npc = layout.getAsJsonObject("npc");
        int nx = npc.get("x").getAsInt(), nz = npc.get("z").getAsInt();
        if (!"lectern".equals(npc.get("marker").getAsString())) throw new AssertionError("NPC stand must be a lectern");
        if (Math.max(Math.abs(nx), Math.abs(nz)) <= 4 || cells.contains(nx + ":" + nz) || nx == 0 || nz == 0) throw new AssertionError("NPC stand is not beside the road");
        var origins = new HashSet<String>();
        for (var entry : villages.getAsJsonArray("villages")) {
            var village = entry.getAsJsonObject();
            if (village.getAsJsonArray("npcs").isEmpty()) throw new AssertionError("Village has no existing NPC");
            String origin = village.get("x").getAsInt() + ":" + village.get("z").getAsInt();
            if (!origins.add(origin)) throw new AssertionError("Village centers are not unique");
            if (village.get("radius").getAsInt() < 8) throw new AssertionError("Village radius cannot hold the layout");
        }
        if (origins.size() != 5) throw new AssertionError("Expected the five existing villages");
        System.out.println("P6 village layout/buildings/npc-stand/no-seed PASS");
    }
}
