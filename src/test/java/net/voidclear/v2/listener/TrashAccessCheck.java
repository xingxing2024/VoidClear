package net.voidclear.v2.listener;
import org.bukkit.inventory.ItemStack;import org.bukkit.Material;import java.util.*;
public final class TrashAccessCheck {
 static final class Stack extends ItemStack {final int kind;final int count;Stack(int k,int n){super();kind=k;count=n;}
  public boolean isEmpty(){return count<=0;}public int getAmount(){return count;}public boolean isSimilar(ItemStack other){return other instanceof Stack s&&s.kind==kind;}}
 static List<ItemStack> list(ItemStack... s){return Arrays.asList(s);}
 public static void main(String[] args){
  var a=new Stack(1,16);var b=new Stack(2,16);
  assert !TrashAccess.permits(false,true,list(a),list(new Stack(1,17)));
  assert !TrashAccess.permits(true,false,list(a),list(new Stack(1,15)));
  assert !TrashAccess.permits(true,false,list(a),list(b));
  assert !TrashAccess.permits(false,true,list(a),list(b));
  assert TrashAccess.permits(true,false,list(a),list(new Stack(1,20)));
  assert TrashAccess.permits(false,true,list(a),list(new Stack(1,3)));
  assert TrashAccess.permits(false,false,list(a,null),list(new Stack(1,8),new Stack(1,8)));
  assert TrashAccess.permits(true,false,list(),list(a));assert !TrashAccess.permits(false,true,list(),list(a));
  assert !TrashAccess.permits(true,false,list(a),list());assert TrashAccess.permits(false,true,list(a),list());
  System.out.println("TrashAccessCheck: ALL OK");
 }
}
