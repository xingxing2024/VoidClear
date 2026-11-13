package net.voidclear.v2.runtime;
import net.voidclear.v2.config.Settings;
import net.voidclear.v2.rule.Decide;
import org.bukkit.*;
import java.lang.reflect.Proxy;
import java.util.*;
public final class SweepControlCheck {
 public static void main(String[] args) {
  Settings s=Settings.blank();var y=new org.bukkit.configuration.file.YamlConfiguration();y.set("sweep.batch",2);y.set("sweep.adaptive.enable",false);s.install(y);
  var next=new ArrayDeque<Runnable>();int[] ran={0},done={0};
  Scheduler scheduler=(Scheduler)Proxy.newProxyInstance(SweepControlCheck.class.getClassLoader(),new Class[]{Scheduler.class},(p,m,a)->switch(m.getName()){
   case "running"->true;case "once"->{((Runnable)a[0]).run();yield true;}case "later"->{next.add((Runnable)a[1]);yield true;}
   case "onChunk"->{next.add((Runnable)a[3]);yield true;}default->throw new AssertionError(m.getName());});
  World world=(World)Proxy.newProxyInstance(SweepControlCheck.class.getClassLoader(),new Class[]{World.class},(p,m,a)->null);
  var chunks=new ArrayList<Chunk>();for(int i=0;i<5;i++){int x=i;chunks.add((Chunk)Proxy.newProxyInstance(SweepControlCheck.class.getClassLoader(),new Class[]{Chunk.class},(p,m,a)->switch(m.getName()){case "getWorld"->world;case "getX"->x;case "getZ"->0;default->throw new AssertionError(m.getName());}));}
  var sweeper=new Sweeper(s.pace(),s.policy(),scheduler,new Decide(s.policy())){
   protected List<Chunk> collect(List<World> w){return chunks;}protected void sweep(Chunk c){ran[0]++;}};
  assert !sweeper.pause();assert sweeper.start(List.of(world),()->done[0]++);
  assert sweeper.pause();assert !sweeper.pause();while(!next.isEmpty())next.remove().run();
  assert ran[0]==2 && done[0]==0 && sweeper.active();assert sweeper.progress().paused();
  assert sweeper.progress().completedChunks()==2 && sweeper.progress().totalChunks()==5;
  assert sweeper.resume();assert !sweeper.resume();while(!next.isEmpty())next.remove().run();
  assert ran[0]==5 && done[0]==1 && !sweeper.active();assert sweeper.progress().completedChunks()==5;
  assert sweeper.start(List.of(world),()->done[0]++);assert sweeper.pause();sweeper.stop();while(!next.isEmpty())next.remove().run();
  assert done[0]==1 && !sweeper.resume();
  System.out.println("SweepControlCheck: ALL OK");
 }
}
