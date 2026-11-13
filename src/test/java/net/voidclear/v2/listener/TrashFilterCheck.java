package net.voidclear.v2.listener;
import org.bukkit.Material;
public final class TrashFilterCheck {
 public static void main(String[] args) {
  assert TrashFilter.parse(new String[]{"trash"})==null;
  var filter=TrashFilter.parse(new String[]{"trash","search","DIAMOND"});
  assert filter.matches(new TrashFilter.Descriptor(TrashFilter.Category.RESOURCES,"minecraft:diamond",16));
  assert !filter.matches(new TrashFilter.Descriptor(TrashFilter.Category.BLOCKS,"minecraft:stone",64));
  var named=TrashFilter.parse(new String[]{"trash","category","工具","神剑"});
  assert named.matches(new TrashFilter.Descriptor(TrashFilter.Category.EQUIPMENT,"minecraft:diamond_sword 神剑",1));
  assert !named.matches(new TrashFilter.Descriptor(TrashFilter.Category.BLOCKS,"神剑",1));
  assert TrashFilter.classify("DIAMOND",false,false)==TrashFilter.Category.RESOURCES;
  assert TrashFilter.classify("DIAMOND_SWORD",false,false)==TrashFilter.Category.EQUIPMENT;
  assert TrashFilter.classify("STONE",true,false)==TrashFilter.Category.BLOCKS;
  assert TrashFilter.classify("APPLE",false,true)==TrashFilter.Category.FOOD;
  for(String[] bad:new String[][]{{"trash","search"},{"trash","search","x".repeat(65)},{"trash","category","invalid"},{"trash","search","x\ny"}}){
   try{TrashFilter.parse(bad);throw new AssertionError("Invalid search accepted");}catch(IllegalArgumentException expected){}
  }
  System.out.println("TrashFilterCheck: ALL OK");
 }
}
