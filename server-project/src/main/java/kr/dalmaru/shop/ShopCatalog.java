package kr.dalmaru.shop;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Temporary NPC prices. Missing item ids throw IllegalArgumentException. Sell price is buy price times 25, truncated. */
public final class ShopCatalog {
	private static final Map<String, Integer> BUY = load();

	private ShopCatalog() { }

	public static int buyPrice(String itemId) {
		Integer price = BUY.get(itemId);
		if (price == null) throw new IllegalArgumentException("Unknown shop item: " + itemId);
		return price;
	}

	public static int sellPrice(String itemId) { return buyPrice(itemId) * 25 / 100; }

	private static Map<String, Integer> load() {
		try (var stream = ShopCatalog.class.getResourceAsStream("/content/shop.json")) {
			if (stream == null) throw new IllegalStateException("Missing shop prices");
			var items = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("items");
			var prices = new HashMap<String, Integer>();
			for (var entry : items) {
				var item = entry.getAsJsonObject();
				String id = item.get("id").getAsString();
				int price = item.get("buyCopper").getAsInt();
				if (id.isBlank() || price < 1 || prices.put(id, price) != null) throw new IllegalStateException("Invalid shop price: " + id);
			}
			return Map.copyOf(prices);
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot load shop prices", exception);
		}
	}
}
