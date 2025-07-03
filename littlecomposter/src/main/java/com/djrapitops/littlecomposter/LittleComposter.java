package com.djrapitops.littlecomposter;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main JavaPlugin class.
 *
 * @author AuroraLS3
 */
public class LittleComposter extends JavaPlugin implements Listener {

    private Logger logger;
    private Map<Material, Double> compostables = Collections.emptyMap();

    @Override
    public void onEnable() {
        logger = getLogger();

        reload();

        getServer().getPluginManager().registerEvents(this, this);

        getCommand("littlecomposter").setExecutor(this);

        logger.log(Level.INFO, "Enabled LittleComposter.");
    }

    private void reload() {
        saveDefaultConfig();
        reloadConfig();
        loadCompostables();
    }

    private void loadCompostables() {
        compostables = new ComposterConfig(logger, getConfig()).loadCompostableMaterials();

        logger.log(Level.INFO, "Loaded " + compostables.size() + " extra compostable items.");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll((Plugin) this);
        logger.log(Level.INFO, "Disabled LittleComposter.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("littlecomposter.reload")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission for this command!");
            return true;
        }
        if (args.length != 0 && args[0].equals("reload")) {
            reload();
            sender.sendMessage(ChatColor.GREEN + "Loaded " + compostables.size() + " recipes.");
        } else {
            sender.sendMessage(new String[]{"> " + ChatColor.GRAY + "LittleComposter Help:",
                    "",
                    ChatColor.GRAY + "  /littlecomposter reload " + ChatColor.WHITE + "Reloads compost materials from config.",
                    "",
                    ">"
            });
        }
        return true;
    }

    @EventHandler
    public void onPlayerInteractComposter(PlayerInteractEvent e) {
        Block destination = e.getClickedBlock();
        if (destination == null || !Material.COMPOSTER.equals(destination.getType())) {
            return;
        }

        ItemStack item = e.getItem();
        if (item == null) return;

        Material type = item.getType();
        if (shouldCompost(type) && increaseComposterLevel(type, destination, true)) {
            int amount = item.getAmount();
            item.setAmount(amount - 1);
        }
    }

    @EventHandler
    public void onHopperInteractComposter(InventoryMoveItemEvent e) {
        // Only handle hopper source inventories
        if (!InventoryType.HOPPER.equals(e.getSource().getType())) return;
        if (!(e.getSource().getHolder() instanceof Hopper)) return;

        Location location = e.getSource().getLocation();
        if (location == null) return;

        Block hopperBlock = location.getBlock();
        Block destination = getHopperDestination(hopperBlock);

        // Check if destination is a composter
        if (destination == null || !Material.COMPOSTER.equals(destination.getType())) return;

        ItemStack item = e.getItem();
        if (item == null) return;

        Material type = item.getType();
        if (shouldCompost(type)) {
            // Check if composter can accept more items
            Levelled composter = (Levelled) destination.getBlockData();
            int maxLevel = composter.getMaximumLevel() - 1; // -1 to avoid full composter
            int currLevel = composter.getLevel();

            if (currLevel >= maxLevel) {
                // Composter is full, cancel the event to prevent items from being moved
                e.setCancelled(true);
                return;
            }

            e.setCancelled(true);

            Hopper hopper = (Hopper) e.getSource().getHolder();
            if (hopper != null) {
                for (int slot = 0; slot < hopper.getInventory().getSize(); slot++) {
                    ItemStack hopperItem = hopper.getInventory().getItem(slot);
                    if (hopperItem != null && hopperItem.getType() == type) {
                        int newAmount = hopperItem.getAmount() - 1;
                        if (newAmount <= 0) {
                            hopper.getInventory().setItem(slot, null);
                        } else {
                            hopperItem.setAmount(newAmount);
                        }

                        // Process the composting (always consumes the item, but may not increase level)
                        increaseComposterLevel(type, destination, false);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Get the block that a hopper is pointing to
     * @param hopperBlock The hopper block
     * @return The destination block, or null if invalid
     */
    private Block getHopperDestination(Block hopperBlock) {
        if (hopperBlock.getBlockData() instanceof Directional) {
            Directional hopper = (Directional) hopperBlock.getBlockData();
            try {
                Faces face = Faces.valueOf(hopper.getFacing().name());
                return hopperBlock.getLocation().clone()
                        .add(face.getVector())
                        .getBlock();
            } catch (IllegalArgumentException e) {
                logger.log(Level.WARNING, "Unknown hopper facing direction: " + hopper.getFacing().name());
                return null;
            }
        }
        return null;
    }

    private boolean shouldCompost(Material type) {
        return compostables.containsKey(type);
    }

    private boolean succeeds(Material type) {
        return ThreadLocalRandom.current().nextDouble() < compostables.getOrDefault(type, 0.0);
    }

    /**
     * Increase level of a composter with a material
     *
     * @param type           Type of the material
     * @param composterBlock Composter
     * @param playSound      Whether to play sound effects
     * @return true if the level was increased, false if the composter is full.
     */
    private boolean increaseComposterLevel(Material type, Block composterBlock, boolean playSound) {
        Levelled composter = (Levelled) composterBlock.getBlockData();
        int maxLevel = composter.getMaximumLevel() - 1; // -1 to avoid full composter
        boolean succeeded = succeeds(type);

        int currLevel = composter.getLevel();
        if (currLevel < maxLevel) {
            if (succeeded) {
                composter.setLevel(currLevel + 1);
                composterBlock.setBlockData(composter);
            }
            if (playSound) {
                composterBlock.getWorld().playSound(composterBlock.getLocation(),
                        succeeded ? Sound.BLOCK_COMPOSTER_FILL_SUCCESS : Sound.BLOCK_COMPOSTER_FILL, 1, 1);
            }
            return true;
        }
        return false;
    }

    private enum Faces {

        NORTH(0, 0, -1),
        SOUTH(0, 0, 1),
        EAST(1, 0, 0),
        WEST(-1, 0, 0),
        UP(0, 1, 0),
        DOWN(0, -1, 0);

        private final Vector vector;

        Faces(int x, int y, int z) {
            vector = new Vector(x, y, z);
        }

        public Vector getVector() {
            return vector;
        }
    }
}