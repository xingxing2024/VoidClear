package net.voidclear.v2.runtime;
import net.voidclear.v2.config.Settings;
import net.voidclear.v2.rule.Decide;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataContainer;
import java.lang.reflect.Proxy;
public final class DropProtectionCheck {
 public static void main(String[] args) {
  Settings settings=Settings.blank(); var yaml=new YamlConfiguration(); yaml.set("drop.enable",true); settings.install(yaml);
  assert settings.policy().dropProtectSeconds()==60;
  var decide=new Decide(settings.policy());
  assert !Sweeper.clearable(decide,settings.policy(),item(0));
  assert !Sweeper.clearable(decide,settings.policy(),item(1199));
  assert Sweeper.clearable(decide,settings.policy(),item(1200));
  assert Sweeper.skipReason(decide,settings.policy(),item(1)).equals("新掉落保护");
  yaml.set("drop.protect-seconds",0); assert Sweeper.clearable(decide,settings.policy(),item(0));
  yaml.set("drop.protect-seconds",Integer.MAX_VALUE); assert !Sweeper.clearable(decide,settings.policy(),item(Integer.MAX_VALUE));
  yaml.set("drop.protect-seconds",-1); assert settings.policy().dropProtectSeconds()==0;
  System.out.println("DropProtectionCheck: ALL OK");
 }
 static Item item(int ticks) {
  World world=(World)Proxy.newProxyInstance(DropProtectionCheck.class.getClassLoader(),new Class[]{World.class},(p,m,a)->m.getName().equals("getName")?"world":null);
  PersistentDataContainer data=(PersistentDataContainer)Proxy.newProxyInstance(DropProtectionCheck.class.getClassLoader(),new Class[]{PersistentDataContainer.class},(p,m,a)->m.getName().equals("has")?false:null);
  return (Item)Proxy.newProxyInstance(DropProtectionCheck.class.getClassLoader(),new Class[]{Item.class},(p,m,a)->switch(m.getName()){
   case "getItemStack"->null;case "isValid"->true;case "getWorld"->world;case "getTicksLived"->ticks;case "getPersistentDataContainer"->data;
   case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0]; default->throw new AssertionError("Unexpected mutation/API: "+m.getName());
  });
 }
}
