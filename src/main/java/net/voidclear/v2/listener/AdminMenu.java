package net.voidclear.v2.listener;

import net.voidclear.VoidClearPlugin;
import net.voidclear.v2.config.GuiSetting;
import net.voidclear.v2.runtime.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Display-only control panel. Shared actions run globally; inventory updates stay on the player thread. */
public final class AdminMenu implements Listener {
    private final VoidClearPlugin plugin;
    private final Map<UUID,View> viewers=new ConcurrentHashMap<>();
    private enum Page { HOME, SETTINGS, PREVIEW, CONFIRM }
    private static final class View implements InventoryHolder {
        final Inventory inventory;
        Page page=Page.HOME;
        int resultPage;
        volatile boolean closed,busy;
        View() { inventory=Bukkit.createInventory(this,54,Component.text("VoidClear 管理面板")); }
        @Override public Inventory getInventory(){return inventory;}
    }
    public AdminMenu(VoidClearPlugin plugin){this.plugin=plugin;}

    public void open(Player player){
        plugin.scheduler().player(player,()->{
            if(!available(player))return;
            if(!player.getItemOnCursor().isEmpty()){message(player,"&e请先放回光标上的物品。");return;}
            View view=new View();render(view);player.openInventory(view.inventory);viewers.put(player.getUniqueId(),view);
        });
    }
    private boolean available(Player player){
        if(!player.isOnline()||!plugin.scheduler().running())return false;
        if(!player.hasPermission("voidclear.admin")){message(player,"&c没有管理权限。");return false;}
        if(plugin.playerTransfers().pending(player)){message(player,"&7正在确认物品操作，请稍候。");return false;}
        return true;
    }
    private boolean current(Player player,View view){
        return !view.closed&&viewers.get(player.getUniqueId())==view&&player.getOpenInventory().getTopInventory()==view.inventory;
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void click(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof View view))return;
        boolean previouslyCancelled=event.isCancelled();event.setCancelled(true);
        if(previouslyCancelled||!(event.getWhoClicked() instanceof Player player)||event.getClick()!=ClickType.LEFT
                ||event.getRawSlot()<0||event.getRawSlot()>=54||view.busy)return;
        int slot=event.getRawSlot();
        Page clickedPage=view.page;
        plugin.scheduler().playerNextTick(player,()->{
            if(!current(player,view)||view.page!=clickedPage||view.busy||!available(player)||!player.getItemOnCursor().isEmpty())return;
            if(slot==49){view.page=Page.HOME;render(view);return;}
            switch(view.page){
                case HOME -> {
                    if(slot==10){view.page=Page.CONFIRM;render(view);}
                    else if(slot==12)global(player,view,()->{if(plugin.sweeper().progress().paused())plugin.sweeper().resume();else plugin.sweeper().pause();});
                    else if(slot==14){view.page=Page.PREVIEW;global(player,view,()->{
                        if(!plugin.preview().active())plugin.preview().start(Bukkit.getWorlds(),()->{});
                    });}
                    else if(slot==16)plugin.trashListener().open(player);
                    else if(slot==28){view.page=Page.SETTINGS;render(view);}
                    else if(slot==30)global(player,view,plugin::reload);
                    else if(slot==32)render(view);
                }
                case CONFIRM -> {
                    if(slot==20){view.page=Page.HOME;global(player,view,()->{
                        if(!plugin.runSweep(plugin.message("sweep.triggered","&7清扫已开始。"),()->{}))throw new IllegalStateException("已有清扫正在执行。");
                    });}
                    else if(slot==24){view.page=Page.HOME;render(view);}
                }
                case SETTINGS -> {
                    int index=switch(slot){case 10->0;case 12->1;case 14->2;case 28->3;case 30->4;case 32->5;default->-1;};
                    if(index>=0)global(player,view,()->plugin.editSetting(GuiSetting.values()[index]));
                }
                case PREVIEW -> {
                    if(slot==45){view.resultPage=Math.max(0,view.resultPage-1);render(view);}
                    else if(slot==53){view.resultPage++;render(view);}
                    else if(slot==4)global(player,view,()->{if(!plugin.preview().active())plugin.preview().start(Bukkit.getWorlds(),()->{});});
                }
            }
        });
    }
    private void global(Player player,View view,Runnable action){
        view.busy=true;
        Scheduler.global(plugin,()->{
            String failure=null;
            try{if(!view.closed&&plugin.scheduler().running())action.run();}
            catch(RuntimeException failed){failure=failed.getMessage();plugin.getLogger().log(java.util.logging.Level.WARNING,"管理面板操作失败",failed);}
            String result=failure;
            plugin.scheduler().player(player,()->{
                view.busy=false;
                if(!current(player,view))return;
                if(result!=null)message(player,"&c操作未完成："+result);
                if(!player.hasPermission("voidclear.admin")){player.closeInventory();return;}
                render(view);
            });
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof View)event.setCancelled(true);}
    @EventHandler
    public void close(InventoryCloseEvent event){
        if(event.getInventory().getHolder() instanceof View view){view.closed=true;viewers.remove(event.getPlayer().getUniqueId(),view);}
    }
    public void refresh(){
        viewers.forEach((id,view)->{
            Player player=Bukkit.getPlayer(id);if(player==null)return;
            plugin.scheduler().player(player,()->{
                if(!current(player,view))return;
                if(!player.hasPermission("voidclear.admin")){player.closeInventory();return;}
                if(!view.busy)render(view);
            });
        });
    }
    public void shutdown(){
        viewers.forEach((id,view)->{view.closed=true;Player player=Bukkit.getPlayer(id);if(player!=null)
            plugin.scheduler().player(player,()->{if(player.getOpenInventory().getTopInventory()==view.inventory)player.closeInventory();});});
        viewers.clear();
    }
    private void render(View view){
        view.inventory.clear();
        if(view.page==Page.HOME){
            var p=plugin.sweeper().progress();
            icon(view,4,Material.COMPASS,"&b运行概况","&7清扫："+(p.active()?(p.paused()?"已暂停":"进行中"):"空闲"),
                    "&7区块："+p.completedChunks()+" / "+p.totalChunks(),"&7批量："+p.batch()+" · 待完成任务："+p.pendingTasks(),
                    "&7下次清扫："+plugin.sweepTimer().leftText(),"&7垃圾箱："+plugin.trash().itemCount()+" 件");
            icon(view,10,Material.LIME_WOOL,"&a开始清扫","&7点击进入确认页");
            icon(view,12,Material.LEVER,p.paused()?"&a继续清扫":"&e暂停清扫","&7暂停后续批次与分片，已开始的操作会正常完成");
            icon(view,14,Material.SPYGLASS,"&b清扫预览","&7只读统计，并在面板中查看结果");
            icon(view,16,Material.CHEST,"&e公共垃圾箱","&7仍按垃圾箱操作权限检查");
            icon(view,28,Material.COMPARATOR,"&b常用设置","&7点击修改并保存，修改前自动备份配置");
            icon(view,30,Material.WRITABLE_BOOK,"&e重载配置","&7重新读取配置、提示与菜单");
            icon(view,32,Material.CLOCK,"&7刷新面板","&7面板也会每秒自动刷新");
        }else if(view.page==Page.CONFIRM){
            icon(view,4,Material.PAPER,"&e确认开始一轮清扫","&7会按当前规则处理已加载区块中的实体");
            icon(view,20,Material.LIME_WOOL,"&a确认开始");icon(view,24,Material.RED_WOOL,"&c取消");
        }else if(view.page==Page.SETTINGS){
            int[] slots={10,12,14,28,30,32};
            for(int i=0;i<GuiSetting.values().length;i++){
                GuiSetting option=GuiSetting.values()[i];
                icon(view,slots[i],i==1?Material.ENCHANTED_BOOK:i==2?Material.SHULKER_BOX:Material.COMPARATOR,
                        "&b"+option.label()+"：&f"+option.value(plugin.settings()),"&7左键切换并保存","&8配置文件会自动备份");
            }
        }else{
            var preview=plugin.preview();
            icon(view,4,Material.SPYGLASS,preview.active()?"&e预览统计中…":"&b预览结果 · 点击重新统计",
                    "&7仅统计当前已加载区块，实际清扫时重新判定","&7失败任务："+preview.lastFailures());
            var results=preview.results().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
            int pages=Math.max(1,(results.size()+27)/28);view.resultPage=Math.min(view.resultPage,pages-1);
            int start=view.resultPage*28;
            for(int i=start;i<Math.min(start+28,results.size());i++){
                var row=results.get(i);var count=row.getValue();var lore=new ArrayList<String>();
                lore.add("&7生物："+count.creatures()+" · 掉落实体："+count.drops()+" · 经验球："+count.experience());
                count.skipped().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(r->lore.add("&8"+r.getKey()+"："+r.getValue()));
                int n=i-start;icon(view,10+(n/7)*9+n%7,Material.GRASS_BLOCK,"&b"+row.getKey(),lore.toArray(String[]::new));
            }
            icon(view,45,Material.ARROW,"&7上一页");icon(view,53,Material.ARROW,"&7下一页 · "+(view.resultPage+1)+"/"+pages);
        }
        icon(view,49,Material.BARRIER,"&7返回总览");
    }
    private static void icon(View view,int slot,Material material,String name,String... lore){
        ItemStack item=new ItemStack(material);var meta=item.getItemMeta();meta.displayName(text(name));
        meta.lore(Arrays.stream(lore).map(AdminMenu::text).toList());item.setItemMeta(meta);view.inventory.setItem(slot,item);
    }
    private static Component text(String text){return LegacyComponentSerializer.legacyAmpersand().deserialize(text);}
    private static void message(Player player,String text){player.sendMessage(text(text));}
}
