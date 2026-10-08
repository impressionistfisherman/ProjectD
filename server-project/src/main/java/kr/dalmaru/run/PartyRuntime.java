package kr.dalmaru.run;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PartyRuntime {
    private final Map<UUID, Party> parties = new HashMap<>();
    private final Map<UUID, UUID> invites = new HashMap<>();
    public void invite(UUID leader, UUID member) { Party party = parties.computeIfAbsent(leader, Party::new); if (!party.leader.equals(leader) || leader.equals(member) || party.members.size() >= 4 || parties.containsKey(member)) throw new IllegalArgumentException("Party invitation was rejected."); invites.put(member, leader); }
    public void accept(UUID member) { UUID leader = invites.remove(member); Party party = leader == null ? null : parties.get(leader); if (party == null || party.members.size() >= 4 || parties.containsKey(member)) throw new IllegalArgumentException("Party invitation expired."); party.members.add(member); parties.put(member, party); }
    public List<UUID> members(UUID player) { Party party = parties.get(player); return party == null ? List.of(player) : List.copyOf(party.members); }
    public void leave(UUID player) { Party party = parties.remove(player); invites.remove(player); if (party == null) return; party.members.remove(player); if (party.members.size() <= 1) { for (UUID member : party.members) parties.remove(member); } else if (party.leader.equals(player)) { party.leader = party.members.getFirst(); } }
    private static final class Party { private UUID leader; private final List<UUID> members = new ArrayList<>(); private Party(UUID leader) { this.leader = leader; members.add(leader); } }
}
