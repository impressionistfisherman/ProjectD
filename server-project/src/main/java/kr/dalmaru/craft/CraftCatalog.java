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
	public record Trial(String materialId, String binding, int quantity, List<String> drops) { }
	public record Clue(String id, String job, int proficiency, String stationId, List<String> candidates, List<Trial> trials) { }
	public record Learned(String id, String job, int proficiency, String stationId, String attribute, List<Input> inputs, Output output, boolean equipment) { }
	public record PrecisionStep(String choice, String materialId, String binding, int quantity) { }
	public record Precision(String id, String job, int proficiency, String stationId, String equipmentItem, List<PrecisionStep> steps) { }

	private static final Map<String, Station> STATIONS = new HashMap<>();
	private static final Map<String, Recipe> RECIPES = new HashMap<>();
	private static final Map<String, Learned> LEARNED = new HashMap<>();
	private static final Map<String, Clue> CLUES = new HashMap<>();
	private static final Map<String, Precision> PRECISION = new HashMap<>();

	static { load(); }

	private CraftCatalog() { }

	public static boolean queuesPrecision(String id) { return PRECISION.containsKey(id); }

	public static Recipe basic(String id) { return RECIPES.get(id); }

	public static Recipe recipe(String id) {
		Recipe recipe = RECIPES.get(id);
		if (recipe == null) throw new IllegalArgumentException("Unknown recipe: " + id);
		return recipe;
	}

	public static Learned learned(String id) {
		Learned recipe = LEARNED.get(id);
		if (recipe == null) throw new IllegalArgumentException("Unknown recipe: " + id);
		return recipe;
	}

	public static Precision precision(String id) {
		Precision recipe = PRECISION.get(id);
		if (recipe == null) throw new IllegalArgumentException("Unknown recipe: " + id);
		return recipe;
	}

	public static Clue clue(String id) {
		Clue clue = CLUES.get(id);
		if (clue == null) throw new IllegalArgumentException("Unknown craft clue: " + id);
		return clue;
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
			if (root.has("learned")) for (var entry : root.getAsJsonArray("learned")) {
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
				boolean equipment = output.get("equipment").getAsBoolean();
				String attribute = recipe.has("attribute") && !recipe.get("attribute").isJsonNull() ? recipe.get("attribute").getAsString() : "";
				int proficiency = recipe.get("proficiency").getAsInt();
				if (id.isBlank() || LEARNED.containsKey(id) || RECIPES.containsKey(id) || inputs.isEmpty() || output.get("itemId").getAsString().isBlank() || quantity < 1 || (equipment && quantity != 1) || proficiency < 1 || proficiency > 100 || (!"FREE".equals(binding) && !"ACCOUNT".equals(binding)) || recipe.get("job").getAsString().isBlank()) throw new IllegalStateException("Invalid learned recipe: " + id);
				LEARNED.put(id, new Learned(id, recipe.get("job").getAsString(), proficiency, stationId, attribute, List.copyOf(inputs), new Output(output.get("itemId").getAsString(), quantity, binding), equipment));
			}
			if (root.has("research")) for (var entry : root.getAsJsonArray("research")) {
				var clue = entry.getAsJsonObject();
				String id = clue.get("id").getAsString();
				var candidates = new ArrayList<String>();
				for (var candidate : clue.getAsJsonArray("candidates")) {
					String recipeId = candidate.getAsString();
					if (!LEARNED.containsKey(recipeId) || candidates.contains(recipeId)) throw new IllegalStateException("Invalid research candidate: " + id);
					candidates.add(recipeId);
				}
				var trials = new ArrayList<Trial>();
				for (var trial : clue.getAsJsonArray("trials")) {
					var line = trial.getAsJsonObject();
					var drops = new ArrayList<String>();
					for (var drop : line.getAsJsonArray("drops")) {
						String recipeId = drop.getAsString();
						if (!candidates.contains(recipeId) || drops.contains(recipeId)) throw new IllegalStateException("Invalid research drop: " + id);
						drops.add(recipeId);
					}
					String binding = line.get("binding").getAsString();
					int quantity = line.get("quantity").getAsInt();
					if (line.get("materialId").getAsString().isBlank() || quantity < 1 || drops.isEmpty() || (!"FREE".equals(binding) && !"ACCOUNT".equals(binding) && !"CHARACTER".equals(binding))) throw new IllegalStateException("Invalid research trial: " + id);
					trials.add(new Trial(line.get("materialId").getAsString(), binding, quantity, List.copyOf(drops)));
				}
				String stationId = clue.has("station") && !clue.get("station").isJsonNull() ? clue.get("station").getAsString() : "";
				int proficiency = clue.get("proficiency").getAsInt();
				if (id.isBlank() || CLUES.containsKey(id) || candidates.size() < 2 || trials.isEmpty() || proficiency < 1 || (!stationId.isEmpty() && !STATIONS.containsKey(stationId))) throw new IllegalStateException("Invalid craft clue: " + id);
				CLUES.put(id, new Clue(id, clue.get("job").getAsString(), proficiency, stationId, List.copyOf(candidates), List.copyOf(trials)));
			}
			if (root.has("precision")) for (var entry : root.getAsJsonArray("precision")) {
				var recipe = entry.getAsJsonObject();
				String id = recipe.get("id").getAsString();
				String stationId = recipe.has("station") && !recipe.get("station").isJsonNull() ? recipe.get("station").getAsString() : "";
				var steps = new ArrayList<PrecisionStep>();
				for (var step : recipe.getAsJsonArray("steps")) {
					var line = step.getAsJsonObject();
					String choice = line.get("choice").getAsString();
					String binding = line.get("binding").getAsString();
					int quantity = line.get("quantity").getAsInt();
					if (choice.isBlank() || steps.stream().anyMatch(existing -> existing.choice().equals(choice)) || line.get("materialId").getAsString().isBlank() || quantity < 1 || (!"FREE".equals(binding) && !"ACCOUNT".equals(binding) && !"CHARACTER".equals(binding))) throw new IllegalStateException("Invalid precision step: " + id);
					steps.add(new PrecisionStep(choice, line.get("materialId").getAsString(), binding, quantity));
				}
				int proficiency = recipe.get("proficiency").getAsInt();
				if (id.isBlank() || PRECISION.containsKey(id) || RECIPES.containsKey(id) || steps.isEmpty() || proficiency < 1 || recipe.get("equipmentItem").getAsString().isBlank() || (!stationId.isEmpty() && !STATIONS.containsKey(stationId))) throw new IllegalStateException("Invalid precision recipe: " + id);
				PRECISION.put(id, new Precision(id, recipe.get("job").getAsString(), proficiency, stationId, recipe.get("equipmentItem").getAsString(), List.copyOf(steps)));
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot load craft recipes", exception);
		}
	}
}
