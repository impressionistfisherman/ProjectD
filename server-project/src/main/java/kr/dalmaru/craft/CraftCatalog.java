package kr.dalmaru.craft;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.dalmaru.account.TownZone;

/** Temporary process and forge recipes. Missing recipe ids throw IllegalArgumentException. */
public final class CraftCatalog {
	public record Input(String itemId, int quantity) { }
	public record Output(String itemId, int quantity, String binding) { }
	public record Station(String id, TownZone zone) { }
	public record Recipe(String id, String stationId, List<Input> inputs, Output output) { }

	private static final Map<String, Station> STATIONS = new HashMap<>();
	private static final Map<String, Recipe> RECIPES = new HashMap<>();

	static { load(); }

	private CraftCatalog() { }

	public static Recipe recipe(String id) {
		Recipe recipe = RECIPES.get(id);
		if (recipe == null) throw new IllegalArgumentException("Unknown recipe: " + id);
		return recipe;
	}

	public static Station station(String id) {
		Station station = STATIONS.get(id);
		if (station == null) throw new IllegalArgumentException("Unknown craft station: " + id);
		return station;
	}

	private static void load() {
		try (var stream = CraftCatalog.class.getResourceAsStream("/content/crafting.json")) {
			if (stream == null) throw new IllegalStateException("Missing craft recipes");
			var root = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			for (var entry : root.getAsJsonArray("stations")) {
				var station = entry.getAsJsonObject();
				String id = station.get("id").getAsString();
				if (id.isBlank() || STATIONS.containsKey(id)) throw new IllegalStateException("Invalid craft station: " + id);
				STATIONS.put(id, new Station(id, new TownZone(station.get("dimension").getAsString(), station.get("x").getAsInt(), station.get("y").getAsInt(), station.get("z").getAsInt(), station.get("radius").getAsInt(), id)));
			}
			for (var entry : root.getAsJsonArray("recipes")) {
				var recipe = entry.getAsJsonObject();
				String id = recipe.get("id").getAsString();
				String stationId = recipe.has("station") && !recipe.get("station").isJsonNull() ? recipe.get("station").getAsString() : "";
				if (!stationId.isEmpty() && !STATIONS.containsKey(stationId)) throw new IllegalStateException("Recipe station is unknown: " + id);
				var inputs = new ArrayList<Input>();
				for (var input : recipe.getAsJsonArray("inputs")) {
					var line = input.getAsJsonObject();
					int quantity = line.get("quantity").getAsInt();
					if (line.get("itemId").getAsString().isBlank() || quantity < 1) throw new IllegalStateException("Invalid craft input: " + id);
					inputs.add(new Input(line.get("itemId").getAsString(), quantity));
				}
				var output = recipe.getAsJsonObject("output");
				String binding = output.get("binding").getAsString();
				int quantity = output.get("quantity").getAsInt();
				if (id.isBlank() || inputs.isEmpty() || output.get("itemId").getAsString().isBlank() || quantity < 1 || (!"FREE".equals(binding) && !"ACCOUNT".equals(binding)) || RECIPES.containsKey(id)) throw new IllegalStateException("Invalid craft recipe: " + id);
				RECIPES.put(id, new Recipe(id, stationId, List.copyOf(inputs), new Output(output.get("itemId").getAsString(), quantity, binding)));
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot load craft recipes", exception);
		}
	}
}
