package kr.dalmaru.combat;

import java.util.*;

/** Server tick based effects; values stay in RPG units until the final engine conversion. */
public final class CombatEffects {
    private record Mark(UUID target,double bonus,long until) {}
    private record Control(String source,double slow,long until) {}
    private static final class Shield {
        final String source;final long order;long until;double remaining;
        Shield(String source,double amount,long until,long order){this.source=source;this.remaining=amount;this.until=until;this.order=order;}
    }
    private final Map<UUID,Mark> marks=new HashMap<>();
    private final Map<UUID,List<Control>> controls=new HashMap<>();
    private final Map<UUID,List<Shield>> shields=new HashMap<>();
    private long sequence;

    public void mark(UUID caster,UUID target,int rank,long tick) {
        mark(caster,target,rank,tick,kr.dalmaru.content.ContentCatalog.current().skill("skill.archer.tracking_mark"));
    }
    public void mark(UUID caster,UUID target,int rank,long tick,kr.dalmaru.content.ContentCatalog.Skill definition) {
        if(rank<1||rank>5)throw new IllegalArgumentException("Invalid mark rank.");
        if(!definition.id().equals("skill.archer.tracking_mark"))throw new IllegalArgumentException("Wrong mark definition.");
        marks.put(caster,new Mark(target,definition.parameter("markBonus",rank),Math.addExact(tick,Math.round(definition.parameter("markTicks",rank)))));
    }
    public double markBonus(UUID caster,UUID target,long tick) {
        Mark mark=marks.get(caster);
        return mark!=null&&mark.until>tick&&mark.target.equals(target)?mark.bonus:0;
    }
    public void control(UUID target,String source,double slow,long until) {
        if(!Double.isFinite(slow)||slow<0||slow>1)throw new IllegalArgumentException("Invalid slow.");
        var effects=controls.computeIfAbsent(target,id->new ArrayList<>());
        effects.removeIf(effect->effect.source.equals(source));effects.add(new Control(source,slow,until));
    }
    public double slow(UUID target,long tick) {
        var effects=controls.get(target);if(effects==null)return 0;
        effects.removeIf(effect->effect.until<=tick);
        if(effects.isEmpty()){controls.remove(target);return 0;}
        return effects.stream().mapToDouble(Control::slow).max().orElse(0);
    }
    public boolean cleanseOne(UUID target,long tick) {
        var effects=controls.get(target);if(effects==null)return false;
        effects.removeIf(effect->effect.until<=tick);
        Control strongest=effects.stream().max(Comparator.comparingDouble(Control::slow).thenComparingLong(Control::until)).orElse(null);
        if(strongest==null)return false;effects.remove(strongest);return true;
    }
    public double shield(UUID target,String source,double amount,double maximumHealth,long tick) {
        return shield(target,source,amount,maximumHealth,tick,160);
    }
    public double shield(UUID target,String source,double amount,double maximumHealth,long tick,long duration) {
        if(duration<1)throw new IllegalArgumentException("Invalid shield duration.");
        long until=Math.addExact(tick,duration);
        if(!Double.isFinite(amount)||amount<0||!Double.isFinite(maximumHealth)||maximumHealth<=0)throw new IllegalArgumentException("Invalid shield.");
        var effects=shields.computeIfAbsent(target,id->new ArrayList<>());expireShields(effects,tick);
        Shield same=effects.stream().filter(effect->effect.source.equals(source)).findFirst().orElse(null);
        if(same!=null&&amount<same.remaining)return 0;
        double prior=same==null?0:same.remaining;
        double others=effects.stream().filter(effect->effect!=same).mapToDouble(effect->effect.remaining).sum();
        double accepted=others==0?amount:Math.min(amount,Math.max(0,maximumHealth*.30-others));
        if(same!=null&&accepted<prior)return 0;
        if(same!=null){same.remaining=accepted;same.until=until;}
        else if(accepted>0)effects.add(new Shield(source,accepted,until,sequence++));
        return Math.max(0,accepted-prior);
    }
    public double absorb(UUID target,double damage,long tick) {
        var effects=shields.get(target);if(effects==null)return damage;
        expireShields(effects,tick);effects.sort(Comparator.comparingLong((Shield s)->s.until).thenComparingLong(s->s.order));
        for(Shield effect:effects){double absorbed=Math.min(damage,effect.remaining);effect.remaining-=absorbed;damage-=absorbed;if(damage<=0)break;}
        effects.removeIf(effect->effect.remaining<=0);if(effects.isEmpty())shields.remove(target);return damage;
    }
    public double shieldRemaining(UUID target,long tick) {
        var effects=shields.get(target);if(effects==null)return 0;expireShields(effects,tick);
        return effects.stream().mapToDouble(effect->effect.remaining).sum();
    }
    private static void expireShields(List<Shield> effects,long tick){effects.removeIf(effect->effect.until<=tick||effect.remaining<=0);}
    public void clear(UUID entity) {
        marks.remove(entity);marks.values().removeIf(mark->mark.target.equals(entity));controls.remove(entity);shields.remove(entity);
    }
    public void expire(long tick) {
        marks.values().removeIf(mark->mark.until<=tick);
        for(UUID id:new ArrayList<>(controls.keySet()))slow(id,tick);
        for(UUID id:new ArrayList<>(shields.keySet()))if(shieldRemaining(id,tick)==0)shields.remove(id);
    }
}
