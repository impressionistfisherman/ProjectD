package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class P5FieldReviveReconnectCheck {
	private P5FieldReviveReconnectCheck() { }

	public static void main(String[] args) throws Exception {
		String networking = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
		int respawn = networking.indexOf("getPlayerList().respawn(player,false,net.minecraft.world.entity.Entity.RemovalReason.KILLED);player.connection.player=player;");
		if (respawn < 0) throw new IllegalStateException("Field revive respawn does not retarget the connection.");
		UUID id = UUID.fromString("00000000-0000-4000-8000-00000000d1ed");
		var occupied = new World();
		var oldBody = occupied.respawnWithoutRetarget(id);
		if (occupied.link.player != oldBody || occupied.byId.get(id) == oldBody) throw new IllegalStateException("Respawn must leave the connection on the old body.");
		occupied.disconnect(oldBody);
		if (occupied.onTab(id) || !occupied.blocksLogin(id)) throw new IllegalStateException("Disconnecting the stale body must hide the tab entry and keep the login map.");
		var cleared = new World();
		var current = cleared.respawnAndRetarget(id);
		cleared.disconnect(current);
		if (cleared.blocksLogin(id) || cleared.onTab(id)) throw new IllegalStateException("Retargeting the connection must release the login map.");
		System.out.println("P5_FIELD_REVIVE_RECONNECT_OK");
	}

	private static final class Body {
		final UUID id;
		Body(UUID id) { this.id = id; }
	}

	private static final class Link {
		Body player;
		Link(Body player) { this.player = player; }
	}

	private static final class World {
		final List<Body> listed = new ArrayList<>();
		final Map<UUID, Body> byId = new LinkedHashMap<>();
		final List<UUID> hiddenFromTab = new ArrayList<>();
		Link link;

		Body respawnWithoutRetarget(UUID id) {
			return respawn(id, false);
		}

		Body respawnAndRetarget(UUID id) {
			return respawn(id, true);
		}

		private Body respawn(UUID id, boolean retarget) {
			var old = new Body(id);
			link = new Link(old);
			listed.add(old);
			byId.put(id, old);
			listed.remove(old);
			var revived = new Body(id);
			revivedLink(revived);
			byId.put(id, revived);
			listed.add(revived);
			if (retarget) link.player = revived;
			return link.player;
		}

		private void revivedLink(Body revived) { }

		void disconnect(Body body) {
			listed.remove(body);
			hiddenFromTab.add(body.id);
			if (byId.get(body.id) == body) byId.remove(body.id);
		}

		boolean onTab(UUID id) { return listed.stream().anyMatch(body -> body.id.equals(id)) && !hiddenFromTab.contains(id); }

		boolean blocksLogin(UUID id) { return byId.containsKey(id); }
	}
}
