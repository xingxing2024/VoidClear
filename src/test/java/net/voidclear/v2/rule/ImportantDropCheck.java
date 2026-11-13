package net.voidclear.v2.rule;
import org.bukkit.inventory.ItemStack;import org.bukkit.inventory.meta.*;import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataContainer;import org.bukkit.NamespacedKey;import java.lang.reflect.Proxy;import java.util.*;
public final class ImportantDropCheck {
 static class Stack extends ItemStack {private final ItemMeta meta;Stack(ItemMeta m){super();meta=m;}public boolean isEmpty(){return false;}public ItemMeta getItemMeta(){return meta;}}
 static ItemMeta meta(Class<?> type,boolean enchant,boolean stored,boolean filled,Set<NamespacedKey> keys){
  var pdc=(PersistentDataContainer)Proxy.newProxyInstance(ImportantDropCheck.class.getClassLoader(),new Class[]{PersistentDataContainer.class},(p,m,a)->m.getName().equals("getKeys")?keys:null);
  return (ItemMeta)Proxy.newProxyInstance(ImportantDropCheck.class.getClassLoader(),new Class[]{type},(p,m,a)->switch(m.getName()){
   case "hasEnchants"->enchant;case "hasStoredEnchants"->stored;case "hasItems"->filled;case "hasBlockState"->false;case "getPersistentDataContainer"->pdc;default->throw new AssertionError(m.getName());});
 }
 public static void main(String[] args){
  var y=new YamlConfiguration();y.set("enchanted",true);y.set("nonempty-containers",true);
  assert ImportantDrops.reason(y,new Stack(meta(ItemMeta.class,true,false,false,Set.of()))).equals("附魔物品保护");
  assert ImportantDrops.reason(y,new Stack(meta(EnchantmentStorageMeta.class,false,true,false,Set.of()))).equals("附魔物品保护");
  assert ImportantDrops.reason(y,new Stack(meta(BundleMeta.class,false,false,true,Set.of()))).equals("非空容器保护");
  assert ImportantDrops.reason(y,new Stack(meta(BundleMeta.class,false,false,false,Set.of())))==null;
  y.set("enchanted",false);assert ImportantDrops.reason(y,new Stack(meta(ItemMeta.class,true,false,false,Set.of())))==null;
  var tagged=new Stack(meta(ItemMeta.class,false,false,false,Set.of(new NamespacedKey("gear","weapon"))));
  y.set("pdc-keys",List.of("other:*"));assert ImportantDrops.reason(y,tagged)==null;
  y.set("pdc-keys",List.of("gear:weapon"));assert ImportantDrops.reason(y,tagged).equals("自定义标记保护");
  y.set("pdc-keys",List.of("gear:*"));assert ImportantDrops.reason(y,tagged).equals("自定义标记保护");
  assert ImportantDrops.reason(null,tagged)==null;
  System.out.println("ImportantDropCheck: ALL OK");
 }
}
