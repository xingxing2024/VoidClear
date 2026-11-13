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
    @Override protected ChunkWork work(Chunk chunk) {
        Entity[] entities = chunk.isLoaded() ? chunk.getEntities() : new Entity[0];
        return new ChunkWork() {
            int offset; long creatures, drops, experience;
            final Map<String,Long> skipped = new LinkedHashMap<>();
            @Override public boolean step() {
                if (!scheduler.running() || !chunk.isLoaded()) return true;
                int end = Math.min(entities.length, offset + pace.sliceEntities());
                for (; offset < end; offset++) {
                    Entity entity = entities[offset];
                    if (!readable(entity)) { skipped.merge("实体已离开所属区域",1L,Long::sum); continue; }
                    String reason = skipReason(decide,policy,entity);
                    if (reason != null) skipped.merge(reason,1L,Long::sum);
                    else if (entity instanceof Item) drops++;
                    else if (entity instanceof ExperienceOrb) experience++;
                    else creatures++;
                }
                if (offset < entities.length) return false;
                long candidates = creatures + drops + experience;
                if (candidates < pace.floor()) {
                    skipped.merge("低于区块阈值",candidates,Long::sum);
                    creatures=0; drops=0; experience=0;
                }
                Counts next = new Counts(creatures,drops,experience,skipped);
                synchronized(results) {
                    results.merge(chunk.getWorld().getName(),next,(a,b)-> {
                        var reasons=new LinkedHashMap<>(a.skipped());
                        b.skipped().forEach((reason,count)->reasons.merge(reason,count,Long::sum));
                        return new Counts(a.creatures()+b.creatures(),a.drops()+b.drops(),a.experience()+b.experience(),reasons);
                    });
                }
                return true;
            }
        };
    }
    public Map<String,Counts> results() { synchronized(results) { return Map.copyOf(results); } }
}
