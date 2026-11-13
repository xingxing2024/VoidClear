package net.voidclear.v2.runtime;

import net.voidclear.v2.config.Sections;
import net.voidclear.v2.rule.Decide;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ExperienceOrb;
import java.util.LinkedHashMap;
import java.util.Map;

/** Uses the sweep dispatcher, but never reconciles sources, touches storage or changes entities. */
public class SweepPreview extends Sweeper {
    public record Counts(long creatures, long drops, long experience, Map<String, Long> skipped) {
        public Counts { skipped = Map.copyOf(skipped); }
    }
    private final Sections.Policy policy;
    private final Sections.Pace pace;
    private final Scheduler scheduler;
    private final Decide decide;
    private final Map<String, Counts> results = new LinkedHashMap<>();
    public SweepPreview(Sections.Pace pace, Sections.Policy policy, Scheduler scheduler, Decide decide) {
        super(pace, policy, scheduler, decide);
        this.pace=pace; this.policy=policy; this.scheduler=scheduler; this.decide=decide;
    }
    @Override public boolean start(java.util.List<org.bukkit.World> worlds, Runnable finished) {
        synchronized(results) {
            if (active() || stopped()) return false;
            results.clear();
        }
        return super.start(worlds, finished);
    }
    @Override protected void sweep(Chunk chunk) {
        if (!scheduler.running() || !chunk.isLoaded()) return;
        long creatures=0, drops=0, experience=0;
        var skipped=new LinkedHashMap<String,Long>();
        var candidates=new java.util.ArrayList<Entity>();
        for(Entity entity:chunk.getEntities()) {
            String reason=skipReason(decide,policy,entity);
            if(reason!=null) skipped.merge(reason,1L,Long::sum); else candidates.add(entity);
        }
        if(candidates.size()<pace.floor()) skipped.merge("低于区块阈值",(long)candidates.size(),Long::sum);
        else for(Entity entity:candidates) {
            if(entity instanceof Item) drops++;
            else if(entity instanceof ExperienceOrb) experience++;
            else creatures++;
        }
        Counts next=new Counts(creatures,drops,experience,skipped);
        synchronized(results) {
            results.merge(chunk.getWorld().getName(),next,(a,b)-> {
                var reasons=new LinkedHashMap<>(a.skipped());
                b.skipped().forEach((reason,count)->reasons.merge(reason,count,Long::sum));
                return new Counts(a.creatures()+b.creatures(),a.drops()+b.drops(),a.experience()+b.experience(),reasons);
            });
        }
    }
    public Map<String,Counts> results() { synchronized(results) { return Map.copyOf(results); } }
}
