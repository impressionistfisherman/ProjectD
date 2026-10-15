package kr.dalmaru.content;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import kr.dalmaru.account.CharacterProfession;
import kr.dalmaru.account.TownZone;

public final class VillageCatalog {
	public record Npc(String id, String name, String appearance, List<String> lines) {
		public Npc {
			lines = List.copyOf(lines);
			if (id.isBlank() || name.isBlank() || !appearance.startsWith("minecraft:villager/") || lines.isEmpty() || lines.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("NPC draft is incomplete: " + id);
		}
	}

	public record Village(String id, String name, String profession, TownZone zone, List<String> routes, List<Npc> npcs) {
		public Village {
			routes = List.copyOf(routes);
			npcs = List.copyOf(npcs);
			if (!profession.isEmpty()) CharacterProfession.valueOf(profession);
			if (npcs.isEmpty()) throw new IllegalArgumentException("Village has no NPC: " + id);
		}
	}

	private static final List<Village> VILLAGES = load();

	private VillageCatalog() { }

	public static List<Village> current() { return VILLAGES; }

	public static Village get(String id) {
		return VILLAGES.stream().filter(village -> village.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown village: " + id));
	}

	private static List<Village> load() {
		try (var stream = VillageCatalog.class.getResourceAsStream("/content/villages.json")) {
			if (stream == null) throw new IllegalStateException("Missing village definitions");
			var root = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			var villages = new ArrayList<Village>();
			for (var entry : root.getAsJsonArray("villages")) {
				var village = entry.getAsJsonObject();
				var npcs = new ArrayList<Npc>();
				for (var npcEntry : village.getAsJsonArray("npcs")) {
					var npc = npcEntry.getAsJsonObject();
					var lines = new ArrayList<String>();
					for (var line : npc.getAsJsonArray("lines")) lines.add(line.getAsString());
					npcs.add(new Npc(npc.get("id").getAsString(), npc.get("name").getAsString(), npc.get("appearance").getAsString(), lines));
				}
				var routes = new ArrayList<String>();
				for (var route : village.getAsJsonArray("routes")) routes.add(route.getAsString());
				villages.add(new Village(village.get("id").getAsString(), village.get("name").getAsString(), village.get("profession").getAsString(), new TownZone(village.get("dimension").getAsString(), village.get("x").getAsInt(), village.get("y").getAsInt(), village.get("z").getAsInt(), village.get("radius").getAsInt()), routes, npcs));
			}
			var ids = villages.stream().map(Village::id).distinct().toList();
			if (ids.size() != villages.size()) throw new IllegalStateException("Village ids are not unique.");
			for (var village : villages) {
				for (var route : village.routes()) if (!ids.contains(route)) throw new IllegalStateException("Village route is unknown: " + route);
				for (var other : villages) {
					if (other == village) continue;
					if (village.zone().contains(other.zone().dimension(), other.zone().x(), other.zone().y(), other.zone().z())) throw new IllegalStateException("Village zones overlap.");
				}
			}
			return List.copyOf(villages);
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot load village definitions", exception);
		}
	}
}
