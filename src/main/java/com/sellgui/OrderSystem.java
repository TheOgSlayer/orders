package com.sellgui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Buy orders: a player asks for X of an item for a total price.
 * The money is taken immediately and refunded if the order is cancelled.
 * Whoever hands over the items receives the money.
 */
public class OrderSystem implements CommandExecutor, Listener {

    static final int MAX_ORDERS = 5;
    static final int MAX_AMOUNT = 2304;               // 36 slots * 64
    static final double MAX_PRICE = 1_000_000_000_000.0;

    private record Order(UUID id, UUID owner, String ownerName, Material material,
                         int amount, double price, long created) {}

    private final SellGUI plugin;
    private final File file;
    private final Map<UUID, Order> orders = new LinkedHashMap<>();
    private final Map<UUID, Map<Material, Integer>> pending = new HashMap<>();

    public OrderSystem(SellGUI plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "orders.yml");
        load();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection os = y.getConfigurationSection("orders");
        if (os != null) {
            for (String key : os.getKeys(false)) {
                try {
                    ConfigurationSection c = os.getConfigurationSection(key);
                    if (c == null) continue;
                    Material mat = Material.matchMaterial(c.getString("material", ""));
                    if (mat == null) continue;
                    UUID id = UUID.fromString(key);
                    orders.put(id, new Order(id, UUID.fromString(c.getString("owner", "")),
                            c.getString("ownerName", "Unknown"), mat,
                            c.getInt("amount"), c.getDouble("price"), c.getLong("created")));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("orders.yml: skipped invalid order '" + key + "'");
                }
            }
        }

        ConfigurationSection ps = y.getConfigurationSection("pending");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    ConfigurationSection c = ps.getConfigurationSection(key);
                    if (c == null) continue;
                    Map<Material, Integer> map = new EnumMap<>(Material.class);
                    for (String m : c.getKeys(false)) {
                        Material mat = Material.matchMaterial(m);
                        if (mat != null) map.put(mat, c.getInt(m));
                    }
                    if (!map.isEmpty()) pending.put(uuid, map);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        plugin.getLogger().info("Loaded " + orders.size() + " open orders.");
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Order o : orders.values()) {
            String k = "orders." + o.id();
            y.set(k + ".owner", o.owner().toString());
            y.set(k + ".ownerName", o.ownerName());
            y.set(k + ".material", o.material().name());
            y.set(k + ".amount", o.amount());
            y.set(k + ".price", o.price());
            y.set(k + ".created", o.created());
        }
        for (Map.Entry<UUID, Map<Material, Integer>> e : pending.entrySet()) {
            for (Map.Entry<Material, Integer> m : e.getValue().entrySet()) {
                y.set("pending." + e.getKey() + "." + m.getKey().name(), m.getValue());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save orders.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ command / join

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /order.");
            return true;
        }
        openMain(player);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        Map<Material, Integer> items = pending.remove(player.getUniqueId());
        if (items == null) return;
        save();
        // small delay so the player is fully loaded in
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Map.Entry<Material, Integer> en : items.entrySet()) {
                give(player, en.getKey(), en.getValue());
                player.sendMessage(Component.text("Your order was filled while you were away: "
                        + en.getValue() + "x " + SellGUI.pretty(en.getKey()), NamedTextColor.GREEN));
            }
        }, 40L);
    }

    // ------------------------------------------------------------------ dialog helpers

    private ActionButton back(Runnable action) {
        return ActionButton.builder(Component.text("Back")).width(220).action(plugin.click(action)).build();
    }

    private ActionButton closeButton() {
        // Esc runs the exit button's action, so Close does nothing but close
        return ActionButton.builder(Component.text("Close")).width(220).build();
    }

    private void show(Player player, List<DialogBody> body, List<DialogInput> inputs,
                      List<ActionButton> actions, ActionButton exit) {
        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(SellGUI.title())
                        .body(body)
                        .inputs(inputs)
                        .canCloseWithEscape(true)
                        .build())
                .type(actions.isEmpty()
                        ? DialogType.notice(exit)
                        : DialogType.multiAction(actions, exit, 1)));
        player.showDialog(dialog);
    }

    private static DialogBody line(String text, NamedTextColor color) {
        return DialogBody.plainMessage(Component.text(text, color));
    }

    private static DialogBody line(Component component) {
        return DialogBody.plainMessage(component);
    }

    private int ordersOf(UUID owner) {
        int n = 0;
        for (Order o : orders.values()) {
            if (o.owner().equals(owner)) n++;
        }
        return n;
    }

    private static String itemLabel(Order o) {
        return SellGUI.pretty(o.material()) + " x" + o.amount();
    }

    // ------------------------------------------------------------------ main menu

    private void openMain(Player player) {
        int mine = ordersOf(player.getUniqueId());
        List<DialogBody> body = List.of(
                line("Buy Orders", NamedTextColor.GOLD),
                line("Your orders: " + mine + "/" + MAX_ORDERS, NamedTextColor.GRAY));

        List<ActionButton> actions = List.of(
                plugin.button(Component.text("Browse Orders"), plugin.click(() -> openBrowse(player))),
                plugin.button(Component.text("Create Order"),
                        plugin.click(() -> openCreate(player, heldItemName(player), "", "", null))),
                plugin.button(Component.text("My Orders (" + mine + "/" + MAX_ORDERS + ")"),
                        plugin.click(() -> openMyOrders(player))));
        show(player, body, List.of(), actions, closeButton());
    }

    private static String heldItemName(Player player) {
        Material m = player.getInventory().getItemInMainHand().getType();
        return m.isAir() ? "" : m.name().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ browse & fill

    private void openBrowse(Player player) {
        List<Order> list = new ArrayList<>();
        for (Order o : orders.values()) {
            if (!o.owner().equals(player.getUniqueId())) list.add(o);
        }
        Collections.reverse(list); // newest first
        if (list.size() > 50) list = list.subList(0, 50);

        List<DialogBody> body = new ArrayList<>();
        body.add(line(list.isEmpty() ? "No orders from other players right now."
                : "Click an order to fill it", NamedTextColor.GRAY));

        List<ActionButton> actions = new ArrayList<>();
        for (Order o : list) {
            UUID id = o.id();
            Component label = Component.text(itemLabel(o) + "  ", NamedTextColor.WHITE)
                    .append(SellGUI.money(o.price()));
            actions.add(plugin.button(label, plugin.click(() -> openFill(player, id, null))));
        }
        show(player, body, List.of(), actions, back(() -> openMain(player)));
    }

    private void openFill(Player player, UUID id, Component error) {
        Order o = orders.get(id);
        if (o == null) {
            player.sendMessage(Component.text("That order no longer exists.", NamedTextColor.RED));
            openBrowse(player);
            return;
        }
        int have = countPlain(player, o.material());

        List<DialogBody> body = new ArrayList<>();
        body.add(line(SellGUI.pretty(o.material()), NamedTextColor.GOLD));
        body.add(line("Wants: " + o.amount(), NamedTextColor.WHITE));
        body.add(line(Component.text("Pays: ", NamedTextColor.WHITE).append(SellGUI.money(o.price()))));
        body.add(line("Ordered by: " + o.ownerName(), NamedTextColor.GRAY));
        body.add(line("You have: " + have, have >= o.amount() ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (error != null) body.add(line(error));

        List<ActionButton> actions = List.of(
                plugin.button(Component.text("Fill Order  ", NamedTextColor.WHITE).append(SellGUI.money(o.price())),
                        plugin.click(() -> fill(player, id))));
        show(player, body, List.of(), actions, back(() -> openBrowse(player)));
    }

    private void fill(Player player, UUID id) {
        Order o = orders.get(id);
        if (o == null) {
            player.sendMessage(Component.text("That order no longer exists.", NamedTextColor.RED));
            openBrowse(player);
            return;
        }
        if (o.owner().equals(player.getUniqueId())) {
            openFill(player, id, Component.text("You can't fill your own order.", NamedTextColor.RED));
            return;
        }
        int have = countPlain(player, o.material());
        if (have < o.amount()) {
            openFill(player, id, Component.text("You need " + (o.amount() - have) + " more "
                    + SellGUI.pretty(o.material()) + ".", NamedTextColor.RED));
            return;
        }
        Economy eco = plugin.getEconomy();
        if (eco == null) return;

        EconomyResponse r = eco.depositPlayer(player, o.price());
        if (!r.transactionSuccess()) {
            openFill(player, id, Component.text("Payment failed: " + r.errorMessage, NamedTextColor.RED));
            return;
        }

        removePlain(player, o.material(), o.amount());
        orders.remove(id);
        deliver(o, player.getName());
        save();

        player.sendMessage(Component.text("+", NamedTextColor.WHITE).append(SellGUI.money(o.price())));
        openBrowse(player);
    }

    /** Gives the ordered items to the order owner (now, or when they next join). */
    private void deliver(Order o, String fillerName) {
        Player owner = Bukkit.getPlayer(o.owner());
        if (owner != null) {
            give(owner, o.material(), o.amount());
            owner.sendMessage(Component.text("Your order for " + itemLabel(o) + " was filled by "
                    + fillerName + ".", NamedTextColor.GREEN));
        } else {
            pending.computeIfAbsent(o.owner(), k -> new EnumMap<>(Material.class))
                    .merge(o.material(), o.amount(), Integer::sum);
        }
    }

    // ------------------------------------------------------------------ create

    private void openCreate(Player player, String item, String amount, String price, Component error) {
        List<DialogBody> body = new ArrayList<>();
        body.add(line("The money is taken now and refunded if you cancel.", NamedTextColor.GRAY));
        body.add(line("Your orders: " + ordersOf(player.getUniqueId()) + "/" + MAX_ORDERS, NamedTextColor.GRAY));
        if (error != null) body.add(line(error));

        List<DialogInput> inputs = List.of(
                DialogInput.text("item", Component.text("Item (e.g. diamond)"))
                        .width(220).initial(item).maxLength(64).build(),
                DialogInput.text("amount", Component.text("Amount"))
                        .width(220).initial(amount).maxLength(6).build(),
                DialogInput.text("price", Component.text("Total price you will pay"))
                        .width(220).initial(price).maxLength(16).build());

        ActionButton place = plugin.button(Component.text("Place Order"),
                DialogAction.customClick((view, audience) -> {
                    String i = view.getText("item");
                    String a = view.getText("amount");
                    String p = view.getText("price");
                    Bukkit.getScheduler().runTask(plugin, () -> placeOrder(player, i, a, p));
                }, ClickCallback.Options.builder().build()));

        show(player, body, inputs, List.of(place), back(() -> openMain(player)));
    }

    private void placeOrder(Player player, String itemRaw, String amountRaw, String priceRaw) {
        String item = itemRaw == null ? "" : itemRaw.trim();
        String amountStr = amountRaw == null ? "" : amountRaw.trim();
        String priceStr = priceRaw == null ? "" : priceRaw.trim();

        if (ordersOf(player.getUniqueId()) >= MAX_ORDERS) {
            openCreate(player, item, amountStr, priceStr, err("You already have " + MAX_ORDERS
                    + " orders. Cancel one first."));
            return;
        }

        Material mat = item.isEmpty() ? null : Material.matchMaterial(item.replace(' ', '_'));
        if (mat == null || mat.isAir() || !mat.isItem()) {
            openCreate(player, item, amountStr, priceStr, err("Unknown item. Use a name like diamond or oak_log."));
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(amountStr);
        } catch (NumberFormatException ex) {
            openCreate(player, item, amountStr, priceStr, err("Amount must be a whole number."));
            return;
        }
        if (amount < 1 || amount > MAX_AMOUNT) {
            openCreate(player, item, amountStr, priceStr, err("Amount must be between 1 and " + MAX_AMOUNT + "."));
            return;
        }

        double price;
        try {
            price = Double.parseDouble(priceStr.replace(",", ""));
        } catch (NumberFormatException ex) {
            openCreate(player, item, amountStr, priceStr, err("Price must be a number."));
            return;
        }
        price = Math.round(price * 100.0) / 100.0;
        if (Double.isNaN(price) || Double.isInfinite(price) || price < 0.01 || price > MAX_PRICE) {
            openCreate(player, item, amountStr, priceStr, err("Enter a valid price."));
            return;
        }

        Economy eco = plugin.getEconomy();
        if (eco == null) return;
        if (!eco.has(player, price)) {
            openCreate(player, item, amountStr, priceStr, err("You don't have enough money."));
            return;
        }
        EconomyResponse r = eco.withdrawPlayer(player, price);
        if (!r.transactionSuccess()) {
            openCreate(player, item, amountStr, priceStr, err("Payment failed: " + r.errorMessage));
            return;
        }

        Order o = new Order(UUID.randomUUID(), player.getUniqueId(), player.getName(), mat,
                amount, price, System.currentTimeMillis());
        orders.put(o.id(), o);
        save();

        player.sendMessage(Component.text("Order placed: " + itemLabel(o) + " for ", NamedTextColor.GREEN)
                .append(SellGUI.money(price)));
        openMyOrders(player);
    }

    private static Component err(String text) {
        return Component.text(text, NamedTextColor.RED);
    }

    // ------------------------------------------------------------------ my orders / cancel

    private void openMyOrders(Player player) {
        List<Order> mine = new ArrayList<>();
        for (Order o : orders.values()) {
            if (o.owner().equals(player.getUniqueId())) mine.add(o);
        }

        List<DialogBody> body = new ArrayList<>();
        body.add(line(mine.isEmpty() ? "You have no open orders."
                : "Click an order to cancel it (" + mine.size() + "/" + MAX_ORDERS + ")", NamedTextColor.GRAY));

        List<ActionButton> actions = new ArrayList<>();
        for (Order o : mine) {
            UUID id = o.id();
            Component label = Component.text(itemLabel(o) + "  ", NamedTextColor.WHITE)
                    .append(SellGUI.money(o.price()));
            actions.add(plugin.button(label, plugin.click(() -> openOrderDetail(player, id))));
        }
        show(player, body, List.of(), actions, back(() -> openMain(player)));
    }

    private void openOrderDetail(Player player, UUID id) {
        Order o = orders.get(id);
        if (o == null || !o.owner().equals(player.getUniqueId())) {
            openMyOrders(player);
            return;
        }
        List<DialogBody> body = List.of(
                line(SellGUI.pretty(o.material()), NamedTextColor.GOLD),
                line("Amount wanted: " + o.amount(), NamedTextColor.WHITE),
                line(Component.text("Money held: ", NamedTextColor.WHITE).append(SellGUI.money(o.price()))));

        List<ActionButton> actions = List.of(
                plugin.button(Component.text("Cancel Order  ", NamedTextColor.WHITE).append(SellGUI.money(o.price())),
                        plugin.click(() -> cancel(player, id))));
        show(player, body, List.of(), actions, back(() -> openMyOrders(player)));
    }

    private void cancel(Player player, UUID id) {
        Order o = orders.get(id);
        if (o == null || !o.owner().equals(player.getUniqueId())) {
            openMyOrders(player);
            return;
        }
        Economy eco = plugin.getEconomy();
        if (eco == null) return;

        EconomyResponse r = eco.depositPlayer(player, o.price());
        if (!r.transactionSuccess()) {
            player.sendMessage(Component.text("Refund failed: " + r.errorMessage, NamedTextColor.RED));
            return;
        }
        orders.remove(id);
        save();

        player.sendMessage(Component.text("+", NamedTextColor.WHITE).append(SellGUI.money(o.price()))
                .append(Component.text(" (order cancelled)", NamedTextColor.GRAY)));
        openMyOrders(player);
    }

    // ------------------------------------------------------------------ inventory helpers
    // Only plain (unnamed, unenchanted, undamaged) items count towards orders.

    private static boolean plain(ItemStack item, Material mat) {
        return item != null && item.getType() == mat && !item.hasItemMeta();
    }

    private int countPlain(Player player, Material mat) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (plain(item, mat)) count += item.getAmount();
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (plain(off, mat)) count += off.getAmount();
        return count;
    }

    private void removePlain(Player player, Material mat, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (!plain(item, mat)) continue;
            int take = Math.min(remaining, item.getAmount());
            if (take >= item.getAmount()) {
                contents[i] = null;
            } else {
                item.setAmount(item.getAmount() - take);
            }
            remaining -= take;
        }
        player.getInventory().setStorageContents(contents);

        if (remaining > 0) {
            ItemStack off = player.getInventory().getItemInOffHand();
            if (plain(off, mat)) {
                int take = Math.min(remaining, off.getAmount());
                if (take >= off.getAmount()) {
                    player.getInventory().setItemInOffHand(null);
                } else {
                    off.setAmount(off.getAmount() - take);
                    player.getInventory().setItemInOffHand(off);
                }
            }
        }
    }

    private void give(Player player, Material mat, int amount) {
        int left = amount;
        int max = Math.max(1, mat.getMaxStackSize());
        while (left > 0) {
            int n = Math.min(left, max);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(mat, n));
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            left -= n;
        }
    }
}
