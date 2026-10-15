package kr.dalmaru.account;

import java.util.UUID;

public record WaypointChannel(UUID character,long generation,String waypoint,String dimension,double x,double y,double z,long started) {
    public static final int CAST_TICKS=160;
    public enum Status { WAIT,READY,CANCEL }
    public Status poll(UUID active,long currentGeneration,String currentDimension,double px,double py,double pz,boolean eligible,boolean inCombat,boolean inDungeon,long tick) {
        double dx=px-x,dy=py-y,dz=pz-z;
        if(!eligible||inCombat||inDungeon||!character.equals(active)||generation!=currentGeneration||!dimension.equals(currentDimension)||!Double.isFinite(dx+dy+dz)||dx*dx+dy*dy+dz*dz>.0001||tick<started)return Status.CANCEL;
        return tick-started>=CAST_TICKS?Status.READY:Status.WAIT;
    }
}
