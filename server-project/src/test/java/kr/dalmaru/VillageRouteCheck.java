package kr.dalmaru;

import java.util.Set;
import kr.dalmaru.content.VillageCatalog;

public final class VillageRouteCheck {
	private VillageRouteCheck() { }

	public static void main(String[] args) throws Exception {
		var villages = VillageCatalog.current();
        var characters=new kr.dalmaru.account.CharacterRepository();
        require(characters.towns().equals(villages.stream().map(VillageCatalog.Village::zone).toList()),"Safety and return zones differ from village configuration.");
		require(villages.size() == 5, "Pilot villages are not the five named towns.");
		var hub = VillageCatalog.get("taepyeong");
		require("태평현".equals(hub.name()) && hub.profession().isEmpty() && hub.routes().equals(java.util.List.of("cheolwi", "cheongnim", "unhak", "heukjo")), "Taepyeong is not the hub.");
		require(job("cheolwi", "철위성", "WARRIOR") && job("cheongnim", "청림촌", "ARCHER") && job("unhak", "운학촌", "MAGE") && job("heukjo", "흑조포", "ROGUE"), "A job town is missing its route or draft.");
		for (var village : villages) {
            require(village.id().equals(village.zone().waypointId()),"Waypoint ID must survive coordinate changes.");
            var zone=village.zone();
            require(characters.inTown(zone.dimension(),zone.x()+zone.radius(),zone.y(),zone.z())&&!characters.inTown(zone.dimension(),zone.x()+zone.radius()+1,zone.y(),zone.z()),"Safety boundary differs from village configuration.");
			var npc = village.npcs().getFirst();
			require(npc.appearance().startsWith("minecraft:villager/") && npc.lines().getFirst().contains("초안"), "NPC appearance or draft line is missing.");
			require(village.zone().contains(village.zone().dimension(), village.zone().x(), village.zone().y(), village.zone().z()), "Village center is outside its own zone.");
		}
		System.out.println("Village routes, temp villagers, and draft lines PASS");
	}

	private static boolean job(String id, String name, String profession) {
		var village = VillageCatalog.get(id);
		return name.equals(village.name()) && profession.equals(village.profession()) && village.routes().equals(java.util.List.of("taepyeong")) && Set.of("cheolwi", "cheongnim", "unhak", "heukjo").contains(id);
	}

	private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
