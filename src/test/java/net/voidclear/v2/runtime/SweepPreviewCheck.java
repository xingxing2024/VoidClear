package net.voidclear.v2.runtime;
import net.voidclear.v2.config.Settings;
import net.voidclear.v2.rule.Decide;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.ArrayList;
public final class SweepPreviewCheck {
 public static void main(String[] args) {
  var settings=Settings.blank();var yaml=new org.bukkit.configuration.file.YamlConfiguration();yaml.set("drop.enable",true);settings.install(yaml);
  var young=DropProtectionCheck.item(100);var old=DropProtectionCheck.item(1200);
  World world=old.getWorld();
  Chunk chunk=(Chunk)Proxy.newProxyInstance(SweepPreviewCheck.class.getClassLoader(),new Class[]{Chunk.class},(p,m,a)->switch(m.getName()){
   case "isLoaded"->true;case "getWorld"->world;case "getX","getZ"->0;case "getEntities"->new Entity[]{young,old};default->throw new AssertionError(m.getName());
  });
  var next=new ArrayList<Runnable>();
  Scheduler scheduler=(Scheduler)Proxy.newProxyInstance(SweepPreviewCheck.class.getClassLoader(),new Class[]{Scheduler.class},(p,m,a)->switch(m.getName()){
   case "folia"->false;case "running"->true;case "once"->{((Runnable)a[0]).run();yield true;}case "later"->{next.add((Runnable)a[1]);yield true;}
   case "onChunk"->{((Runnable)a[3]).run();yield true;}default->throw new AssertionError(m.getName());
  });
  SweepPreview preview=new SweepPreview(settings.pace(),settings.policy(),scheduler,new Decide(settings.policy())){
   @Override protected List<Chunk> collect(List<World> worlds){return List.of(chunk);}
  };
  int[] completed={0};assert preview.start(List.of(world),()->completed[0]++);assert completed[0]==1;
  var counts=preview.results().get("world");assert counts.drops()==1 && counts.creatures()==0 && counts.experience()==0;
  assert counts.skipped().get("新掉落保护")==1L;assert preview.lastFailures()==0;
  yaml.set("sweep.floor",2);assert preview.start(List.of(world),()->completed[0]++);
  counts=preview.results().get("world");assert counts.drops()==0 && counts.skipped().get("低于区块阈值")==1L;
  assert completed[0]==2;assert !preview.hasResult() || preview.lastTotal()==0;
  System.out.println("SweepPreviewCheck: ALL OK (mutations rejected by fixture)");
 }
}
