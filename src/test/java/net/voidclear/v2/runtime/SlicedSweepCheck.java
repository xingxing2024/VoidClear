package net.voidclear.v2.runtime;

import net.voidclear.v2.config.Settings;
import net.voidclear.v2.rule.Decide;
import org.bukkit.*;
import org.bukkit.entity.*;
import java.lang.reflect.Proxy;
import java.util.*;

/** A single dense chunk must yield, park on pause, and settle once after its final slice. */
public final class SlicedSweepCheck {
    public static void main(String[] args) {
        run(false, false); run(true, false); run(false, true); floor();
        System.out.println("SlicedSweepCheck: ALL OK");
    }
    static void run(boolean stop, boolean reject) {
        var f = new Fixture(); f.reject = reject;
        var sweep = f.sweeper(); int[] done = {0};
        sweep.start(List.of(f.world), () -> done[0]++);
        assert f.removed == 2 : "a dense chunk must stop after two entities";
        if (reject) { assert done[0] == 1 && sweep.lastFailures() == 1; return; }
        assert sweep.progress().completedChunks() == 0 && sweep.active();
        if (stop) {
            sweep.stop(); f.pump(); assert f.removed == 2 && done[0] == 0; return;
        }
        assert sweep.pause(); f.pump(); assert f.removed == 2 && done[0] == 0;
        assert sweep.resume(); assert !sweep.resume(); f.pump();
        assert f.removed == 7 && done[0] == 1 && !sweep.active();
        assert sweep.progress().completedChunks() == 1 && sweep.lastTotal() == 7;
    }
    static void floor() {
        var f = new Fixture(); f.yaml.set("sweep.floor",8);
        var sweep=f.sweeper(); int[] done={0};sweep.start(List.of(f.world),()->done[0]++);
        assert f.inspected<=2 && f.removed==0;f.pump();assert f.removed==0&&done[0]==1;
    }
    static final class Fixture {
        int removed,inspected; boolean reject;final ArrayDeque<Runnable> queue=new ArrayDeque<>();
        final Settings settings=Settings.blank();final org.bukkit.configuration.file.YamlConfiguration yaml=new org.bukkit.configuration.file.YamlConfiguration();
        final World world=(World)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{World.class},(p,m,a)->m.getName().equals("getName")?"world":null);
        final Entity[] entities=new Entity[7];final Chunk chunk;
        final Scheduler scheduler;
        Fixture(){
            yaml.set("sweep.slice.entities",2);settings.install(yaml);
            for(int i=0;i<7;i++){boolean[] valid={true};entities[i]=(Entity)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ExperienceOrb.class},(p,m,a)->switch(m.getName()){
                case "isValid"->{inspected++;yield valid[0];}case "getWorld"->world;case "remove"->{assert valid[0];valid[0]=false;removed++;yield null;}
                default->throw new AssertionError(m.getName());});}
            chunk=(Chunk)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Chunk.class},(p,m,a)->switch(m.getName()){
                case "getWorld"->world;case "getX","getZ"->0;case "isLoaded"->true;case "getEntities"->entities;default->throw new AssertionError(m.getName());});
            scheduler=(Scheduler)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Scheduler.class},(p,m,a)->switch(m.getName()){
                case "running"->true;case "folia"->false;case "once"->{((Runnable)a[0]).run();yield true;}
                case "onChunk"->{((Runnable)a[3]).run();yield true;}
                case "onChunkLater"->{if(reject)yield false;queue.add((Runnable)a[4]);yield true;}
                case "later"->{queue.add((Runnable)a[1]);yield true;}default->throw new AssertionError(m.getName());});
        }
        Sweeper sweeper(){return new Sweeper(settings.pace(),settings.policy(),scheduler,new Decide(settings.policy())){
            protected List<Chunk> collect(List<World> w){return List.of(chunk);}};}
        void pump(){int guard=100;while(!queue.isEmpty()){assert guard-->0;int before=removed;queue.remove().run();assert removed-before<=2;}}
    }
}
