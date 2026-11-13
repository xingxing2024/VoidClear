package net.voidclear.v2.store;
import java.nio.file.Files;import java.nio.file.Path;import java.util.*;import java.util.logging.Logger;
public final class FilteredWithdrawalCheck {
 public static void main(String[] args)throws Exception {
  var file=Files.createTempDirectory(Path.of("target"),"selection-").resolve("bin.db");
  try(var store=TrashStore.sqlite(file,Logger.getLogger("SelectionCheck"))){
   store.collect(new byte[]{1},()->{});store.collect(new byte[]{2},()->{});store.collect(new byte[]{3},()->{});
   var all=store.page(0,3);
   assert store.isCurrentSelection(List.of(all.get(1)));
   assert !store.isCurrentSelection(List.of(all.get(1),all.get(1)));var player=UUID.randomUUID();var session=UUID.randomUUID();var op=UUID.randomUUID();
   assert !store.withdrawPlayerSelection(player,session,op,null,new byte[]{1},List.of(all.get(0),all.get(0)),Arrays.asList(null,null));
   assert store.size()==3 && store.playerCheckpoint(player)==null;
   assert !store.withdrawPlayerSelection(player,session,op,null,new byte[]{1},List.of(new TrashStore.Entry(all.get(0).id(),new byte[]{9})),Collections.singletonList(null));
   assert !store.withdrawPlayerSelection(player,session,op,null,new byte[]{1},List.of(new TrashStore.Entry(999,new byte[]{1})),Collections.singletonList(null));
   assert store.withdrawPlayerSelection(player,session,op,null,new byte[]{1},List.of(all.get(1)),Collections.singletonList(null));
   assert store.size()==2 && store.playerCheckpoint(player).state().equals("PREPARED");
   assert !store.isCurrentSelection(List.of(all.get(1)));
   assert store.isCurrentSelection(List.of(all.get(0),all.get(2)));
   assert TrashStore.samePage(store.page(0,3),List.of(all.get(0),all.get(2))):"Unselected rows changed";
   assert !store.withdrawPlayerSelection(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,new byte[]{1},List.of(all.get(1)),Collections.singletonList(null));
   try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath())) { db.createStatement().executeUpdate("DELETE FROM voidclear_trash_items_item_fences"); }
   assert !store.withdrawPlayerSelection(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,new byte[]{1},List.of(all.get(0)),Collections.singletonList(null)):"Legacy no-fence operation not blocked";
   store.checkpointPlayer(player,session,op,"DONE");
   try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath())){
    db.createStatement().executeUpdate("CREATE TRIGGER fail_journal BEFORE INSERT ON voidclear_trash_items_player_ops BEGIN SELECT RAISE(ABORT,'injected'); END");
    try{store.withdrawPlayerSelection(player,session,UUID.randomUUID(),op,new byte[]{1},List.of(all.get(0)),Collections.singletonList(null));throw new AssertionError("Journal failure ignored");}catch(IllegalStateException expected){}
   }
   assert store.size()==2 && store.playerCheckpoint(player).operation().equals(op):"Failed journal changed inventory";
  }
  System.out.println("FilteredWithdrawalCheck: ALL OK");
 }
}
