package kr.dalmaru.run;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Temporary auction terms are frozen when a run completes. */
public final class AuctionCatalog {
    public record Terms(String displayName, String itemId, String binding, int saleCopper,
                        int initialSeconds, int extensionWindowSeconds, int extensionSeconds,
                        int maximumSeconds, int incrementPercent) {
        public Terms {
            if (displayName == null || !displayName.contains("임시") || itemId == null || itemId.isBlank()
                    || !"ACCOUNT".equals(binding) || saleCopper < 1 || initialSeconds < 1
                    || extensionWindowSeconds < 1 || extensionSeconds < 1 || maximumSeconds < initialSeconds
                    || extensionWindowSeconds > maximumSeconds || extensionSeconds > maximumSeconds
                    || incrementPercent < 1 || incrementPercent > 100)
                throw new IllegalArgumentException("Invalid auction terms.");
        }
        public long minimumBid(int current) {
            return current == 0 ? saleCopper : current + Math.max(1L, (current * (long) incrementPercent + 99) / 100);
        }
        public JsonObject json() {
            var result = new JsonObject();
            result.addProperty("displayName", displayName); result.addProperty("itemId", itemId);
            result.addProperty("binding", binding); result.addProperty("saleCopper", saleCopper);
            result.addProperty("initialSeconds", initialSeconds); result.addProperty("extensionWindowSeconds", extensionWindowSeconds);
            result.addProperty("extensionSeconds", extensionSeconds); result.addProperty("maximumSeconds", maximumSeconds);
            result.addProperty("incrementPercent", incrementPercent); return result;
        }
    }
    private static final Terms CURRENT = load();
    private AuctionCatalog() { }
    public static Terms current() { return CURRENT; }
    static Terms parse(String json) {
        var value = JsonParser.parseString(json).getAsJsonObject();
        return new Terms(value.get("displayName").getAsString(), value.get("itemId").getAsString(), value.get("binding").getAsString(),
                value.get("saleCopper").getAsBigDecimal().intValueExact(), value.get("initialSeconds").getAsBigDecimal().intValueExact(),
                value.get("extensionWindowSeconds").getAsBigDecimal().intValueExact(), value.get("extensionSeconds").getAsBigDecimal().intValueExact(),
                value.get("maximumSeconds").getAsBigDecimal().intValueExact(), value.get("incrementPercent").getAsBigDecimal().intValueExact());
    }
    private static Terms load() {
        try (var stream = AuctionCatalog.class.getResourceAsStream("/content/auction.json")) {
            if (stream == null) throw new IllegalStateException("Missing auction terms.");
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException error) { throw new IllegalStateException("Cannot load auction terms.", error); }
    }
}
