package kr.dalmaru.run;

import java.util.*;
import kr.dalmaru.content.BeaconMechanics;
import net.minecraft.world.phys.Vec3;

/** Server-thread channel state. Time advances only while the encounter is active. */
public final class BrazierMechanic {
    private record Channel(int brazier, Vec3 origin, long started) {}
    public record TickResult(Set<UUID> completed, Map<UUID,RunMechanicAccess.CancelReason> cancelled, Set<UUID> unsynchronized) {
        public TickResult { completed=Set.copyOf(completed); cancelled=Map.copyOf(cancelled); unsynchronized=Set.copyOf(unsynchronized); }
    }
    private final BeaconMechanics.Brazier rules;
    private final boolean[] lit;
    private final int simultaneous;
    private final Map<UUID,Channel> channels=new LinkedHashMap<>();
    private long tick;

    public BrazierMechanic(BeaconMechanics.Brazier rules,int partySize) {
        if(partySize<1||partySize>4)throw new IllegalArgumentException("Invalid party size");
        this.rules=Objects.requireNonNull(rules);
        int tier=Math.min(partySize,3)-1;
        lit=new boolean[rules.counts().get(tier)]; simultaneous=rules.simultaneous().get(tier);
    }
    public RunMechanicAccess.Result start(UUID player,int brazier,Vec3 position) {
        Objects.requireNonNull(player); Objects.requireNonNull(position);
        if(brazier<0||brazier>=lit.length)return RunMechanicAccess.Result.WRONG_PHASE;
        if(lit[brazier])return RunMechanicAccess.Result.ALREADY_LIT;
        if(channels.containsKey(player))return RunMechanicAccess.Result.ALREADY_CHANNELING;
        if(channels.values().stream().anyMatch(c->c.brazier==brazier))return RunMechanicAccess.Result.BUSY;
        channels.put(player,new Channel(brazier,position,tick));
        return RunMechanicAccess.Result.STARTED;
    }
    public boolean cancel(UUID player) { return channels.remove(player)!=null; }
    public boolean moved(UUID player,Vec3 position) {
        var c=channels.get(player);
        return c!=null&&c.origin.distanceToSqr(position)>=rules.cancelDistance()*rules.cancelDistance();
    }
    public int count() { return lit.length; }
    public boolean lit(int index) { return lit[index]; }
    public boolean complete() { for(boolean value:lit)if(!value)return false; return true; }
    public UUID owner(int index) { return channels.entrySet().stream().filter(e->e.getValue().brazier==index).map(Map.Entry::getKey).findFirst().orElse(null); }
    public int remaining(UUID player) { var c=channels.get(player); return c==null?0:(int)Math.max(0,rules.channelTicks()-(tick-c.started)); }
    public boolean channeling() { return !channels.isEmpty(); }

    public TickResult tick(Map<UUID,Vec3> positions) {
        tick++;
        var cancelled=new LinkedHashMap<UUID,RunMechanicAccess.CancelReason>();
        for(var entry:new ArrayList<>(channels.entrySet())) {
            var position=positions.get(entry.getKey());
            if(position==null)cancelled.put(entry.getKey(),RunMechanicAccess.CancelReason.DISCONNECT);
            else if(moved(entry.getKey(),position))cancelled.put(entry.getKey(),RunMechanicAccess.CancelReason.MOVED);
        }
        cancelled.keySet().forEach(channels::remove);
        var completed=new LinkedHashSet<UUID>();
        // Completion windows must overlap the final second; a lone leftover brazier needs one player.
        for(var candidate:new ArrayList<>(channels.values())) {
            if(tick-candidate.started<rules.channelTicks())continue;
            var group=channels.entrySet().stream().filter(e->tick-e.getValue().started>=rules.channelTicks()
                    &&e.getValue().started>=candidate.started&&e.getValue().started-candidate.started<=rules.overlapTicks()).toList();
            int unlit=0;for(boolean value:lit)if(!value)unlit++;
            if(group.size()<Math.min(simultaneous,unlit))continue;
            for(var entry:group){lit[entry.getValue().brazier]=true;completed.add(entry.getKey());channels.remove(entry.getKey());}
        }
        var unsynchronized=new LinkedHashSet<UUID>();
        for(var entry:channels.entrySet())if(tick-entry.getValue().started>=rules.channelTicks()+rules.overlapTicks())unsynchronized.add(entry.getKey());
        unsynchronized.forEach(channels::remove);
        return new TickResult(completed,cancelled,unsynchronized);
    }
}
