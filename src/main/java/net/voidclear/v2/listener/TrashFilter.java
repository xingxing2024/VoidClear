package net.voidclear.v2.listener;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Pure query and compact item description; no inventory or database effects. */
public record TrashFilter(Category category, String query) {
    public enum Category {
        ALL("全部"), BLOCKS("方块"), RESOURCES("资源"), EQUIPMENT("工具装备"), FOOD("食物"), OTHER("其他");
        private final String label;
        Category(String label){this.label=label;}
        public String label(){return label;}
    }
    public record Descriptor(Category category,String text,int amount) { }
    public TrashFilter {
        if(category==null || query==null || query.length()>64 || query.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("搜索词最多64字符，不能包含控制字符");
        query=query.strip().toLowerCase(Locale.ROOT);
    }
    public static List<String> categories(){return List.of("all","blocks","resources","equipment","food","other");}
    public static TrashFilter parse(String[] args) {
        if(args.length==1) return null;
        if(args.length>=3 && args[1].equalsIgnoreCase("search")) {
            String text=String.join(" ",Arrays.copyOfRange(args,2,args.length));
            if(text.isBlank()) throw new IllegalArgumentException("请输入搜索词");
            return new TrashFilter(Category.ALL,text);
        }
        if(args.length>=3 && args[1].equalsIgnoreCase("category")) {
            Category category=switch(args[2].toLowerCase(Locale.ROOT)) {
                case "all","全部"->Category.ALL; case "blocks","方块"->Category.BLOCKS;
                case "resources","资源","矿物"->Category.RESOURCES; case "equipment","工具","装备","工具装备"->Category.EQUIPMENT;
                case "food","食物"->Category.FOOD; case "other","其他"->Category.OTHER;
                default->throw new IllegalArgumentException("未知分类");
            };
            return new TrashFilter(category,String.join(" ",Arrays.copyOfRange(args,3,args.length)));
        }
        throw new IllegalArgumentException("用法：/vc trash [search <名称或材质> | category <分类> [关键词]]");
    }
    public boolean matches(Descriptor item) {
        return (category==Category.ALL || category==item.category()) && item.text().toLowerCase(Locale.ROOT).contains(query);
    }
    public String label(){return category.label()+(query.isEmpty()?"":" · "+query);}
    public static Descriptor describe(ItemStack item) {
        String name="";
        if(item.hasItemMeta() && item.getItemMeta().hasDisplayName() && item.getItemMeta().displayName()!=null)
            name=PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()).replaceAll("(?i)§[0-9A-FK-ORX]","");
        return new Descriptor(classify(item.getType()),item.getType().getKey()+" "+name,item.getAmount());
    }
    public static Category classify(Material material) {
        return classify(material.name(), material.isBlock(), material.isEdible());
    }
    static Category classify(String name, boolean block, boolean food) {
        if(name.matches(".*_(SWORD|PICKAXE|AXE|SHOVEL|HOE|HELMET|CHESTPLATE|LEGGINGS|BOOTS)")
                || List.of("BOW","CROSSBOW","TRIDENT","MACE","SHIELD","ELYTRA","FISHING_ROD","SHEARS","FLINT_AND_STEEL","BRUSH").contains(name)) return Category.EQUIPMENT;
        if(food) return Category.FOOD;
        if(block) return Category.BLOCKS;
        if(name.endsWith("_INGOT") || name.endsWith("_NUGGET") || name.startsWith("RAW_")
                || List.of("DIAMOND","EMERALD","REDSTONE","COAL","CHARCOAL","LAPIS_LAZULI","QUARTZ","AMETHYST_SHARD","NETHERITE_SCRAP","IRON","GOLD").contains(name)) return Category.RESOURCES;
        return Category.OTHER;
    }
}
