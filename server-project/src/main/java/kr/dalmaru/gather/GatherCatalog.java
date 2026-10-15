package kr.dalmaru.gather;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import kr.dalmaru.account.TownZone;

/** Temporary gather nodes. Missing node ids throw IllegalArgumentException. Vanilla block breaks are not nodes. */
public final class GatherCatalog {
	public record Node(String id, TownZone zone, String itemId, int quantity, String binding, int cooldownSeconds, int lifeXp) { }

	private static final Map<String, Node> NODES = load();

	private GatherCatalog() { }

	public static Node node(String id) {
		Node node = NODES.get(id);
		if (node == null) throw new IllegalArgumentException("Unknown gather node: " + id);
		return node;
	}

	private static Map<String, Node> load() {
		try (var stream = GatherCatalog.class.getResourceAsStream("/content/gathering.json")) {
			if (stream == null) throw new IllegalStateException("Missing gather nodes");
			var entries = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("nodes");
			var nodes = new HashMap<String, Node>();
			for (var entry : entries) {
				var item = entry.getAsJsonObject();
				String id = item.get("id").getAsString();
				String binding = item.get("binding").getAsString();
				int quantity = item.get("quantity").getAsInt();
				int cooldown = item.get("cooldownSeconds").getAsInt();
				int lifeXp = item.get("lifeXp").getAsInt();
				if (id.isBlank() || item.get("itemId").getAsString().isBlank() || quantity < 1 || cooldown < 1 || lifeXp < 0 || (!"FREE".equals(binding) && !"ACCOUNT".equals(binding)) || nodes.containsKey(id)) throw new IllegalStateException("Invalid gather node: " + id);
				var zone = new TownZone(item.get("dimension").getAsString(), item.get("x").getAsInt(), item.get("y").getAsInt(), item.get("z").getAsInt(), item.get("radius").getAsInt(), id);
				nodes.put(id, new Node(id, zone, item.get("itemId").getAsString(), quantity, binding, cooldown, lifeXp));
			}
			return Map.copyOf(nodes);
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot load gather nodes", exception);
		}
	}
}
