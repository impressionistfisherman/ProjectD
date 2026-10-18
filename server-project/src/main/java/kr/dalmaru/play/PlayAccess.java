package kr.dalmaru.play;

import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.account.AccountVault;
import kr.dalmaru.craft.BasicCraft;
import kr.dalmaru.gather.Gathering;
import kr.dalmaru.mail.MailboxClaim;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;
import kr.dalmaru.run.AuctionRepository;
import kr.dalmaru.shop.NpcShop;
import kr.dalmaru.trade.DirectTrade;

/** Routes play packets to existing server functions. Location arguments are the server position. */
public final class PlayAccess {
	private static final AccountVault VAULT = new AccountVault();
	private static final NpcShop SHOP = new NpcShop();
	private static final Gathering GATHER = new Gathering();
	private static final BasicCraft CRAFT = new BasicCraft();
	private static final DirectTrade TRADE = new DirectTrade();
	private static final MailboxClaim MAIL = new MailboxClaim();
	private static final AuctionRepository AUCTION = new AuctionRepository();

	public record Outcome(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }

	private PlayAccess() { }

	public static Outcome act(UUID accountId, long generation, long requestId, UUID characterId, String dimension, int x, int y, int z, RequestEnvelope envelope) throws SQLException {
		var body = envelope.payload();
		return switch (envelope.operation()) {
			case "vault_move" -> {
				requireSize(body, 3);
				var done = VAULT.actJournaled(accountId, generation, requestId, characterId, text(body, "assetId"), integer(body, "quantity"), text(body, "direction"), envelope);
				yield new Outcome(done.result(), done.receipt(), done.replayed());
			}
			case "shop_trade" -> {
				requireSize(body, 3);
				var done = SHOP.actJournaled(accountId, generation, requestId, characterId, text(body, "itemId"), integer(body, "quantity"), text(body, "direction"), dimension, x, y, z, envelope);
				yield new Outcome(done.result(), done.receipt(), done.replayed());
			}
			case "gather_node" -> {
				requireSize(body, 1);
				var done = GATHER.actJournaled(accountId, generation, requestId, characterId, text(body, "nodeId"), dimension, x, y, z, envelope);
				yield new Outcome(done.result(), done.receipt(), done.replayed());
			}
			case "craft_recipe" -> {
				requireSize(body, 1);
				var done = CRAFT.actJournaled(accountId, generation, requestId, characterId, text(body, "recipeId"), dimension, x, y, z, envelope);
				yield new Outcome(done.result(), done.receipt(), done.replayed());
			}
			case "direct_trade" -> trade(accountId, generation, requestId, characterId, body, envelope);
			case "view_auction" -> {
				requireSize(body, 1);
				var shot = AUCTION.forRun(accountId, generation, UUID.fromString(text(body, "runId")));
				if (shot == null) throw new IllegalArgumentException("No auction.");
				yield new Outcome(shot.json(), readReceipt(envelope), false);
			}
			case "bid_auction" -> {
				requireSize(body, 2);
				var done = AUCTION.bidJournaled(accountId, generation, requestId, characterId, UUID.fromString(text(body, "auctionId")), integer(body, "copper"), envelope);
				yield new Outcome(done.result(), done.receipt(), done.replayed());
			}
			case "leave_auction" -> {
				requireSize(body, 1);
				var done = AUCTION.leaveJournaled(accountId, generation, requestId, characterId, UUID.fromString(text(body, "auctionId")), envelope);
				yield new Outcome(done.result(), done.receipt(), done.replayed());
			}
			case "claim_mail" -> {
				requireSize(body, 1);
				var done = MAIL.actJournaled(accountId, generation, requestId, characterId, UUID.fromString(text(body, "eventId")), envelope);
				yield new Outcome(done.result(), done.receipt(), done.replayed());
			}
			default -> throw new IllegalArgumentException("Unsupported play action.");
		};
	}

	private static Outcome trade(UUID accountId, long generation, long requestId, UUID characterId, JsonObject body, RequestEnvelope envelope) throws SQLException {
		String action = text(body, "action");
		UUID offer = null;
		UUID partner = null;
		String item = "";
		int quantity = 0;
		int version = 0;
		switch (action) {
			case "pending" -> {
				requireSize(body, 1);
				var found = TRADE.pending(accountId);
				if (found == null) throw new IllegalArgumentException("No trade offer.");
				return new Outcome(found, readReceipt(envelope), false);
			}
			case "open" -> {
				requireSize(body, 4);
				partner = UUID.fromString(text(body, "partnerAccountId"));
				item = text(body, "itemId");
				quantity = integer(body, "quantity");
			}
			case "deposit" -> {
				requireSize(body, 4);
				offer = UUID.fromString(text(body, "offerId"));
				item = text(body, "itemId");
				quantity = integer(body, "quantity");
			}
			case "withdraw", "cancel" -> {
				requireSize(body, 2);
				offer = UUID.fromString(text(body, "offerId"));
			}
			case "confirm" -> {
				requireSize(body, 3);
				offer = UUID.fromString(text(body, "offerId"));
				version = integer(body, "version");
			}
			default -> throw new IllegalArgumentException("Invalid trade action.");
		}
		var done = TRADE.actJournaled(accountId, generation, requestId, characterId, action, offer, partner, item, quantity, version, envelope);
		return new Outcome(done.result(), done.receipt(), done.replayed());
	}

	private static RequestJournal.Receipt readReceipt(RequestEnvelope envelope) {
		return new RequestJournal.Receipt(envelope.requestId(), new UUID(0, 0), "accepted");
	}

	private static void requireSize(JsonObject body, int size) {
		if (body.size() != size) throw new IllegalArgumentException("Invalid play body.");
	}

	private static String text(JsonObject body, String key) {
		if (body == null || !body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isString() || body.get(key).getAsString().isBlank()) throw new IllegalArgumentException("Invalid play body.");
		return body.get(key).getAsString();
	}

	private static int integer(JsonObject body, String key) {
		if (body == null || !body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("Invalid play body.");
		try { return body.get(key).getAsBigDecimal().intValueExact(); }
		catch (ArithmeticException error) { throw new IllegalArgumentException("Invalid play body.", error); }
	}
}
