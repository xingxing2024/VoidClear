package net.voidclear.v2.listener;

import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;

/** Permission gates use the entire proposed bin delta, including swaps, drag and double-click. */
final class TrashAccess {
    static boolean open(Player player) { return player.hasPermission("voidclear.trash.open"); }
    static boolean search(Player player) { return open(player) && player.hasPermission("voidclear.trash.search"); }

    static boolean permits(boolean deposit, boolean withdraw, List<ItemStack> before, List<ItemStack> after) {
        if (deposit && withdraw) return true;
        var types = new ArrayList<ItemStack>();
        for (var list : List.of(before, after)) {
            for (var stack : list) {
                if (empty(stack) || types.stream().anyMatch(s -> s.isSimilar(stack))) continue;
                types.add(stack);
            }
        }
        for (var type : types) {
            long change = count(after, type) - count(before, type);
            if (change > 0 && !deposit || change < 0 && !withdraw) return false;
        }
        return true;
    }

    private static long count(List<ItemStack> stacks, ItemStack type) {
        long n = 0;
        for (var stack : stacks) if (!empty(stack) && stack.isSimilar(type)) n += stack.getAmount();
        return n;
    }

    private static boolean empty(ItemStack stack) { return stack == null || stack.isEmpty(); }
}
