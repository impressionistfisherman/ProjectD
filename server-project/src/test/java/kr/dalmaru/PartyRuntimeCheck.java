package kr.dalmaru;

import java.util.UUID;
import kr.dalmaru.run.PartyRuntime;

public final class PartyRuntimeCheck { public static void main(String[] args) { PartyRuntime parties = new PartyRuntime(); UUID leader = UUID.randomUUID(), member = UUID.randomUUID(); parties.invite(leader, member); parties.accept(member); if (parties.members(leader).size() != 2 || !parties.members(member).contains(leader)) throw new IllegalStateException("Party was not formed."); parties.leave(member); if (parties.members(leader).size() != 1) throw new IllegalStateException("Party leave was not applied."); System.out.println("Party runtime verified."); } }
