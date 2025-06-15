package Core.chunkAnalyzer;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ChunkAnalyzer extends JavaPlugin implements Listener, TabCompleter {

    private FileConfiguration config;
    private String language;
    private boolean showGreenChunks;
    private boolean deepAnalyze;
    private static final NamespacedKey CHUNK_KEY = new NamespacedKey("chunkanalyzer", "chunk_data");
    private static final NamespacedKey WORLD_FILTER_KEY = new NamespacedKey("chunkanalyzer", "world_filter");
    private ChunkDataCache chunkCache;
    private BukkitTask scanTask;
    private final Queue<Chunk> scanQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, Long> lastScanTimes = new ConcurrentHashMap<>();
    private static final int SCAN_BATCH_SIZE = 5;
    private static final long SCAN_COOLDOWN = 30000;

    private final Map<UUID, ChunkLocation> entityLocations = new ConcurrentHashMap<>();
    private final Map<UUID, EntityType> entityTypes = new ConcurrentHashMap<>();

    private static final Set<Material> SCAN_MATERIALS = new HashSet<>();
    private static final Set<Material> REDSTONE_MATERIALS = new HashSet<>();
    private static final Set<Material> UPDATABLE_MATERIALS = new HashSet<>();
    private static final Set<Material> LIGHT_SOURCE_MATERIALS = new HashSet<>();

    static {
        Collections.addAll(SCAN_MATERIALS,
                Material.HOPPER,
                Material.FARMLAND,
                Material.BUBBLE_COLUMN,
                Material.FIRE,
                Material.SOUL_FIRE,
                Material.END_PORTAL,
                Material.NETHER_PORTAL,
                Material.COMPOSTER,
                Material.RESPAWN_ANCHOR,
                Material.CAULDRON,
                Material.REDSTONE_LAMP,
                Material.SEA_LANTERN,
                Material.GLOWSTONE,
                Material.TORCH,
                Material.WALL_TORCH,
                Material.SOUL_TORCH,
                Material.SOUL_WALL_TORCH,
                Material.LANTERN,
                Material.SEA_PICKLE,
                Material.CAMPFIRE,
                Material.SOUL_CAMPFIRE
        );

        Collections.addAll(REDSTONE_MATERIALS,
                Material.REDSTONE_WIRE,
                Material.REDSTONE_TORCH,
                Material.REDSTONE_WALL_TORCH,
                Material.REPEATER,
                Material.COMPARATOR,
                Material.LEVER,
                Material.STONE_BUTTON,
                Material.OAK_BUTTON,
                Material.SPRUCE_BUTTON,
                Material.BIRCH_BUTTON,
                Material.JUNGLE_BUTTON,
                Material.ACACIA_BUTTON,
                Material.DARK_OAK_BUTTON,
                Material.OBSERVER,
                Material.PISTON,
                Material.STICKY_PISTON,
                Material.TARGET,
                Material.TRIPWIRE_HOOK,
                Material.DAYLIGHT_DETECTOR,
                Material.REDSTONE_BLOCK,
                Material.DISPENSER,
                Material.DROPPER
        );
        SCAN_MATERIALS.addAll(REDSTONE_MATERIALS);

        Collections.addAll(UPDATABLE_MATERIALS,
                Material.FARMLAND,
                Material.BUBBLE_COLUMN,
                Material.FIRE,
                Material.SOUL_FIRE,
                Material.END_PORTAL,
                Material.NETHER_PORTAL,
                Material.COMPOSTER,
                Material.RESPAWN_ANCHOR,
                Material.CAULDRON
        );

        Collections.addAll(LIGHT_SOURCE_MATERIALS,
                Material.REDSTONE_LAMP,
                Material.SEA_LANTERN,
                Material.GLOWSTONE,
                Material.TORCH,
                Material.WALL_TORCH,
                Material.SOUL_TORCH,
                Material.SOUL_WALL_TORCH,
                Material.LANTERN,
                Material.SEA_PICKLE,
                Material.CAMPFIRE,
                Material.SOUL_CAMPFIRE
        );
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfiguration();
        getServer().getPluginManager().registerEvents(this, this);

        chunkCache = new ChunkDataCache(this);
        chunkCache.loadCache();

        initializeExistingEntities();
        startBackgroundScanner();

        PluginCommand command = Objects.requireNonNull(getCommand("ca"));
        command.setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(getMessage("player_only"));
                return true;
            }
            if (args.length == 0 || !args[0].equalsIgnoreCase("menu")) {
                sender.sendMessage(getMessage("usage"));
                return true;
            }
            openChunkMenu(player, 0, player.getWorld());
            return true;
        });
        command.setTabCompleter(this);
    }

    private void initializeExistingEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) {
                    trackEntity(entity);
                }
            }
        }
    }

    private void trackEntity(Entity entity) {
        UUID uuid = entity.getUniqueId();
        entityTypes.put(uuid, entity.getType());
        updateEntityLocation(entity);
    }

    private void untrackEntity(Entity entity) {
        UUID uuid = entity.getUniqueId();
        ChunkLocation oldLocation = entityLocations.remove(uuid);
        entityTypes.remove(uuid);

        if (oldLocation != null) {
            updateChunkEntityCount(oldLocation.world, oldLocation.x, oldLocation.z, -1,
                    isPlayer(entity) ? -1 : 0,
                    isArmorStand(entity) ? -1 : 0);
        }
    }

    private void updateEntityLocation(Entity entity) {
        UUID uuid = entity.getUniqueId();
        Chunk chunk = entity.getLocation().getChunk();
        ChunkLocation newLocation = new ChunkLocation(
                chunk.getWorld().getName(),
                chunk.getX(),
                chunk.getZ()
        );

        ChunkLocation oldLocation = entityLocations.put(uuid, newLocation);

        if (oldLocation != null && !oldLocation.equals(newLocation)) {
            updateChunkEntityCount(oldLocation.world, oldLocation.x, oldLocation.z, -1,
                    isPlayer(entity) ? -1 : 0,
                    isArmorStand(entity) ? -1 : 0);
        }

        updateChunkEntityCount(newLocation.world, newLocation.x, newLocation.z, 1,
                isPlayer(entity) ? 1 : 0,
                isArmorStand(entity) ? 1 : 0);
    }

    private boolean isPlayer(Entity entity) {
        return entity instanceof Player;
    }

    private boolean isArmorStand(Entity entity) {
        return entity instanceof ArmorStand;
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        trackEntity(event.getEntity());
    }

    @EventHandler
    public void onEntityDespawn(EntityDeathEvent event) {
        untrackEntity(event.getEntity());
    }

    @EventHandler
    public void onEntityMove(EntityTeleportEvent event) {
        updateEntityLocation(event.getEntity());
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        updateEntityLocation(event.getPlayer());
    }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (event.getEntity().isValid()) {
                updateEntityLocation(event.getEntity());
            }
        }, 2L);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (!entityLocations.containsKey(entity.getUniqueId())) {
                trackEntity(entity);
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entityLocations.containsKey(entity.getUniqueId())) {
                untrackEntity(entity);
            }
        }
    }

    private void updateChunkEntityCount(String worldName, int chunkX, int chunkZ,
                                        int entityDelta, int playerDelta, int armorStandDelta) {
        ChunkDataCache.MutableChunkData data = chunkCache.getMutableChunkData(worldName, chunkX, chunkZ);
        if (data == null) return;

        data.entityCount += entityDelta;
        data.playerCount += playerDelta;
        data.armorStandCount += armorStandDelta;

        data.isLagMachine = data.entityCount > 500 ||
                data.armorStandCount > 50 ||
                data.redstoneCount > 100 ||
                data.hopperCount > 20;

        data.score = calculateScore(data);
    }

    private int calculateScore(ChunkDataCache.MutableChunkData data) {
        int score = data.playerCount * 20 +
                (data.entityCount - data.playerCount) +
                data.tileEntityCount * 10 +
                data.armorStandCount * 2;

        if (deepAnalyze) {
            score += data.redstoneCount * 3 +
                    data.hopperCount * 5 +
                    data.updatableCount * 2 +
                    data.lightSources;
        }

        return score;
    }

    private void reloadConfiguration() {
        reloadConfig();
        config = getConfig();
        language = config.getString("language", "en");
        showGreenChunks = config.getBoolean("show-green-chunks", false);
        deepAnalyze = config.getBoolean("deep-analyze", true);
    }

    private void startBackgroundScanner() {
        scanTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (scanQueue.isEmpty()) {
                    repopulateScanQueue();
                }

                for (int i = 0; i < SCAN_BATCH_SIZE && !scanQueue.isEmpty(); i++) {
                    Chunk chunk = scanQueue.poll();
                    if (chunk != null && chunk.isLoaded()) {
                        scanChunk(chunk);
                    }
                }
            }
        }.runTaskTimer(this, 100L, 20L);
    }

    private void repopulateScanQueue() {
        long now = System.currentTimeMillis();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                String chunkKey = getChunkKey(chunk);
                Long lastScan = lastScanTimes.get(chunkKey);

                if (lastScan == null || now - lastScan > SCAN_COOLDOWN) {
                    scanQueue.add(chunk);
                }
            }
        }
    }

    private String getChunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
    }

    private void scanChunk(Chunk chunk) {
        String chunkKey = getChunkKey(chunk);
        lastScanTimes.put(chunkKey, System.currentTimeMillis());

        ChunkDataCache.MutableChunkData cachedData = chunkCache.getMutableChunkData(
                chunk.getWorld().getName(),
                chunk.getX(),
                chunk.getZ()
        );

        boolean needsDeepScan = deepAnalyze &&
                (cachedData == null ||
                        cachedData.score > 100 ||
                        cachedData.isLagMachine);

        ChunkData data = new ChunkData(chunk, needsDeepScan);
        chunkCache.updateChunkData(data);
    }

    @Override
    public void onDisable() {
        if (scanTask != null && !scanTask.isCancelled()) {
            scanTask.cancel();
        }
        chunkCache.saveCache();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Collections.singletonList("menu");
        }
        return Collections.emptyList();
    }

    private void openChunkMenu(Player player, int page, World worldFilter) {
        List<ChunkData> chunks = analyzeChunks(worldFilter);
        int totalPages = chunks.isEmpty() ? 1 : (int) Math.ceil(chunks.size() / 45.0);
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        String title = getMessage("menu_title", page + 1);
        Inventory inv = Bukkit.createInventory(null, 54, title);

        int start = page * 45;
        int end = Math.min(start + 45, chunks.size());

        for (int i = start; i < end; i++) {
            inv.addItem(createChunkItem(chunks.get(i)));
        }

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            if (prevMeta != null) {
                prevMeta.setDisplayName(ChatColor.GREEN + getMessage("button_previous"));
                prev.setItemMeta(prevMeta);
                inv.setItem(45, prev);
            }
        }

        createWorldButton(inv, 47, Material.GRASS_BLOCK,
                getMessage("world_overworld"), World.Environment.NORMAL, worldFilter);
        createWorldButton(inv, 48, Material.NETHERRACK,
                getMessage("world_nether"), World.Environment.NETHER, worldFilter);
        createWorldButton(inv, 49, Material.END_STONE,
                getMessage("world_the_end"), World.Environment.THE_END, worldFilter);

        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            if (nextMeta != null) {
                nextMeta.setDisplayName(ChatColor.GREEN + getMessage("button_next"));
                next.setItemMeta(nextMeta);
                inv.setItem(53, next);
            }
        }

        player.openInventory(inv);
    }

    private void createWorldButton(Inventory inv, int slot, Material material,
                                   String worldName, World.Environment env, World currentFilter) {
        ItemStack button = new ItemStack(material);
        ItemMeta meta = button.getItemMeta();
        if (meta == null) return;

        meta.setDisplayName(ChatColor.WHITE + worldName);

        List<String> lore = new ArrayList<>();
        if (currentFilter != null && currentFilter.getEnvironment() == env) {
            lore.add(ChatColor.GOLD + getMessage("button_current_world"));
        }
        meta.setLore(lore);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(WORLD_FILTER_KEY, PersistentDataType.STRING, env.name());

        button.setItemMeta(meta);
        inv.setItem(slot, button);
    }

    private List<ChunkData> analyzeChunks(World worldFilter) {
        List<ChunkData> chunks = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (worldFilter != null && !world.equals(worldFilter)) continue;
            for (Chunk chunk : world.getLoadedChunks()) {
                ChunkDataCache.CachedChunkData cachedData = chunkCache.getCachedChunkData(
                        world.getName(),
                        chunk.getX(),
                        chunk.getZ()
                );
                if (cachedData == null) continue;

                ChunkData data = new ChunkData(chunk, cachedData);
                if (data.isLagMachine() || data.getScore() > 50 || showGreenChunks) {
                    chunks.add(data);
                }
            }
        }
        chunks.sort(Comparator.comparing(ChunkData::isLagMachine).reversed()
                .thenComparing(Comparator.comparingInt(ChunkData::getScore).reversed()));
        return chunks;
    }

    private ItemStack createChunkItem(ChunkData data) {
        Material material;
        if (data.isLagMachine()) {
            material = Material.MAGMA_BLOCK;
        } else if (data.getScore() > 500) {
            material = Material.RED_WOOL;
        } else if (data.getScore() > 200) {
            material = Material.ORANGE_WOOL;
        } else if (data.getScore() > 50) {
            material = Material.YELLOW_WOOL;
        } else {
            material = Material.GREEN_WOOL;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = getItemMeta(data, item);
        if (meta == null) return item;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(CHUNK_KEY, PersistentDataType.STRING,
                data.world.getName() + ";" + data.chunkX + ";" + data.chunkZ);

        item.setItemMeta(meta);
        return item;
    }

    private ItemMeta getItemMeta(ChunkData data, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        meta.setDisplayName(ChatColor.WHITE + getMessage("chunk_info", data.chunkX, data.chunkZ));

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + getMessage("world", data.world.getName()));
        lore.add(ChatColor.GRAY + getMessage("coordinates", data.centerX, data.centerZ));
        lore.add(ChatColor.GRAY + getMessage("entities", data.entityCount));
        lore.add(ChatColor.GRAY + getMessage("players", data.playerCount));
        lore.add(ChatColor.GRAY + getMessage("tile_entities", data.tileEntityCount));
        lore.add(ChatColor.GRAY + getMessage("armor_stands", data.armorStandCount));

        if (deepAnalyze) {
            lore.add(ChatColor.GRAY + getMessage("redstone_blocks", data.redstoneCount));
            lore.add(ChatColor.GRAY + getMessage("hoppers", data.hopperCount));
            lore.add(ChatColor.GRAY + getMessage("updatable_blocks", data.updatableCount));
            lore.add(ChatColor.GRAY + getMessage("light_sources", data.lightSources));
        }

        lore.add(ChatColor.GOLD + getMessage("load_score", data.getScore()));
        if (data.isLagMachine()) {
            lore.add(ChatColor.RED + getMessage("lag_machine_warning", data.chunkX, data.chunkZ));
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + getMessage("click_to_teleport"));
        meta.setLore(lore);
        return meta;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        String baseTitle = getMessage("menu_title", 1).replaceAll("1$", "").trim();
        if (!title.startsWith(baseTitle)) return;

        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();

        String pageStr = title.replace(baseTitle, "").replaceAll("[^0-9]", "");
        int currentPage = pageStr.isEmpty() ? 0 : Integer.parseInt(pageStr) - 1;

        if (slot == 45 && item.getType() == Material.ARROW) {
            World worldFilter = getWorldFilterFromInventory(event.getInventory());
            player.closeInventory();
            openChunkMenu(player, currentPage - 1, worldFilter);
        }
        else if (slot == 53 && item.getType() == Material.ARROW) {
            World worldFilter = getWorldFilterFromInventory(event.getInventory());
            player.closeInventory();
            openChunkMenu(player, currentPage + 1, worldFilter);
        }
        else if (slot == 47 || slot == 48 || slot == 49) {
            PersistentDataContainer pdc = Objects.requireNonNull(item.getItemMeta()).getPersistentDataContainer();
            if (pdc.has(WORLD_FILTER_KEY, PersistentDataType.STRING)) {
                String envName = pdc.get(WORLD_FILTER_KEY, PersistentDataType.STRING);
                try {
                    World.Environment env = World.Environment.valueOf(envName);
                    World world = Bukkit.getWorlds().stream()
                            .filter(w -> w.getEnvironment() == env)
                            .findFirst()
                            .orElse(null);
                    player.closeInventory();
                    openChunkMenu(player, 0, world);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        else if (slot < 45) {
            PersistentDataContainer pdc = Objects.requireNonNull(item.getItemMeta()).getPersistentDataContainer();
            if (pdc.has(CHUNK_KEY, PersistentDataType.STRING)) {
                String data = pdc.get(CHUNK_KEY, PersistentDataType.STRING);
                if (data != null) {
                    String[] parts = data.split(";");
                    World world = Bukkit.getWorld(parts[0]);
                    int chunkX = Integer.parseInt(parts[1]);
                    int chunkZ = Integer.parseInt(parts[2]);

                    if (world != null) {
                        teleportToChunk(player, world, chunkX, chunkZ);
                        player.sendMessage(ChatColor.GREEN + getMessage("teleport", chunkX, chunkZ));
                    }
                }
            }
        }
    }

    private World getWorldFilterFromInventory(Inventory inv) {
        for (int slot : new int[]{47, 48, 49}) {
            ItemStack item = inv.getItem(slot);
            if (item != null && item.hasItemMeta()) {
                PersistentDataContainer pdc = Objects.requireNonNull(item.getItemMeta()).getPersistentDataContainer();
                if (pdc.has(WORLD_FILTER_KEY, PersistentDataType.STRING)) {
                    String envName = pdc.get(WORLD_FILTER_KEY, PersistentDataType.STRING);
                    for (World world : Bukkit.getWorlds()) {
                        if (world.getEnvironment().name().equals(envName)) {
                            return world;
                        }
                    }
                }
            }
        }
        return null;
    }


    private void teleportToChunk(Player player, World world, int chunkX, int chunkZ) {
        int x = chunkX * 16 + 8;
        int z = chunkZ * 16 + 8;
        int y = world.getHighestBlockYAt(x, z) + 1;
        Location location = new Location(world, x, y, z);
        player.teleport(location);
    }

    private String getMessage(String key, Object... args) {
        String path = "messages." + language + "." + key;
        String message = config.getString(path);

        if (message == null) {
            message = config.getString("messages.en." + key);

            if (message == null) {
                getLogger().warning("Missing translation key: " + key);
                return "[" + key + "]";
            }
        }

        try {
            return String.format(message, args);
        } catch (Exception e) {
            return message;
        }
    }

    private static class ChunkData {
        private final World world;
        private final int chunkX;
        private final int chunkZ;
        private final int entityCount;
        private final int playerCount;
        private final int tileEntityCount;
        private final int armorStandCount;
        private final int centerX;
        private final int centerZ;
        private final boolean isLagMachine;
        private final int redstoneCount;
        private final int hopperCount;
        private final int updatableCount;
        private final int lightSources;
        private final int score;

        public ChunkData(Chunk chunk, boolean deepAnalyze) {
            this.world = chunk.getWorld();
            this.chunkX = chunk.getX();
            this.chunkZ = chunk.getZ();
            this.centerX = chunk.getX() * 16 + 8;
            this.centerZ = chunk.getZ() * 16 + 8;

            this.entityCount = chunk.getEntities().length;
            this.playerCount = (int) Arrays.stream(chunk.getEntities())
                    .filter(e -> e instanceof Player)
                    .count();
            this.tileEntityCount = chunk.getTileEntities().length;
            this.armorStandCount = (int) Arrays.stream(chunk.getEntities())
                    .filter(e -> e instanceof ArmorStand)
                    .count();

            int rsCount = 0;
            int hCount = 0;
            int updCount = 0;
            int lightCount = 0;

            if (deepAnalyze) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                            Block block = chunk.getBlock(x, y, z);
                            Material type = block.getType();

                            if (!SCAN_MATERIALS.contains(type)) continue;

                            if (REDSTONE_MATERIALS.contains(type)) {
                                rsCount++;
                            }

                            if (type == Material.HOPPER) {
                                hCount++;
                            }

                            if (UPDATABLE_MATERIALS.contains(type)) {
                                updCount++;
                            }

                            if (LIGHT_SOURCE_MATERIALS.contains(type)) {
                                lightCount++;
                            }
                        }
                    }
                }
            }

            this.redstoneCount = rsCount;
            this.hopperCount = hCount;
            this.updatableCount = updCount;
            this.lightSources = lightCount;

            this.isLagMachine = entityCount > 500 || armorStandCount > 50 ||
                    redstoneCount > 100 || hopperCount > 20;

            this.score = calculateScore(deepAnalyze);
        }

        public ChunkData(Chunk chunk, ChunkDataCache.CachedChunkData cachedData) {
            this.world = chunk.getWorld();
            this.chunkX = chunk.getX();
            this.chunkZ = chunk.getZ();
            this.centerX = chunk.getX() * 16 + 8;
            this.centerZ = chunk.getZ() * 16 + 8;

            this.entityCount = cachedData.entityCount();
            this.playerCount = cachedData.playerCount();
            this.tileEntityCount = cachedData.tileEntityCount();
            this.armorStandCount = cachedData.armorStandCount();
            this.redstoneCount = cachedData.redstoneCount();
            this.hopperCount = cachedData.hopperCount();
            this.updatableCount = cachedData.updatableCount();
            this.lightSources = cachedData.lightSources();
            this.score = cachedData.score();
            this.isLagMachine = cachedData.isLagMachine();
        }

        private int calculateScore(boolean deepAnalyze) {
            int score = playerCount * 20 +
                    (entityCount - playerCount) +
                    tileEntityCount * 10 +
                    armorStandCount * 2;

            if (deepAnalyze) {
                score += redstoneCount * 3 +
                        hopperCount * 5 +
                        updatableCount * 2 +
                        lightSources;
            }

            return score;
        }

        public int getScore() {
            return score;
        }

        public boolean isLagMachine() {
            return isLagMachine;
        }
    }

    private static class ChunkDataCache {
        private final ChunkAnalyzer plugin;
        private final Map<String, Map<String, MutableChunkData>> cache = new ConcurrentHashMap<>();

        public record CachedChunkData(int entityCount, int playerCount, int tileEntityCount, int armorStandCount,
                                      int redstoneCount, int hopperCount, int updatableCount, int lightSources, int score,
                                      boolean isLagMachine) {
        }

        private static class MutableChunkData {
            int entityCount;
            int playerCount;
            int tileEntityCount;
            int armorStandCount;
            int redstoneCount;
            int hopperCount;
            int updatableCount;
            int lightSources;
            int score;
            boolean isLagMachine;

            CachedChunkData toImmutable() {
                return new CachedChunkData(
                        entityCount, playerCount, tileEntityCount, armorStandCount,
                        redstoneCount, hopperCount, updatableCount, lightSources,
                        score, isLagMachine
                );
            }
        }

        public ChunkDataCache(ChunkAnalyzer plugin) {
            this.plugin = plugin;
        }

        public void loadCache() {
            File cacheFile = new File(plugin.getDataFolder(), "cache.yml");
            if (!cacheFile.exists()) {
                return;
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(cacheFile);
            for (String worldName : config.getKeys(false)) {
                ConfigurationSection worldSection = config.getConfigurationSection(worldName);
                if (worldSection == null) continue;
                Map<String, MutableChunkData> worldCache = new ConcurrentHashMap<>();
                cache.put(worldName, worldCache);
                for (String chunkKey : worldSection.getKeys(false)) {
                    ConfigurationSection chunkSection = worldSection.getConfigurationSection(chunkKey);
                    if (chunkSection == null) continue;
                    int entityCount = chunkSection.getInt("entityCount");
                    int playerCount = chunkSection.getInt("playerCount");
                    int tileEntityCount = chunkSection.getInt("tileEntityCount");
                    int armorStandCount = chunkSection.getInt("armorStandCount");
                    int redstoneCount = chunkSection.getInt("redstoneCount", 0);
                    int hopperCount = chunkSection.getInt("hopperCount", 0);
                    int updatableCount = chunkSection.getInt("updatableCount", 0);
                    int lightSources = chunkSection.getInt("lightSources", 0);
                    int score = chunkSection.getInt("score");
                    boolean isLagMachine = chunkSection.getBoolean("isLagMachine");

                    MutableChunkData data = new MutableChunkData();
                    data.entityCount = entityCount;
                    data.playerCount = playerCount;
                    data.tileEntityCount = tileEntityCount;
                    data.armorStandCount = armorStandCount;
                    data.redstoneCount = redstoneCount;
                    data.hopperCount = hopperCount;
                    data.updatableCount = updatableCount;
                    data.lightSources = lightSources;
                    data.score = score;
                    data.isLagMachine = isLagMachine;

                    worldCache.put(chunkKey, data);
                }
            }
        }

        public void saveCache() {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<String, Map<String, MutableChunkData>> worldEntry : cache.entrySet()) {
                String worldName = worldEntry.getKey();
                for (Map.Entry<String, MutableChunkData> chunkEntry : worldEntry.getValue().entrySet()) {
                    String chunkKey = chunkEntry.getKey();
                    MutableChunkData data = chunkEntry.getValue();
                    String path = worldName + "." + chunkKey;
                    config.set(path + ".entityCount", data.entityCount);
                    config.set(path + ".playerCount", data.playerCount);
                    config.set(path + ".tileEntityCount", data.tileEntityCount);
                    config.set(path + ".armorStandCount", data.armorStandCount);
                    config.set(path + ".redstoneCount", data.redstoneCount);
                    config.set(path + ".hopperCount", data.hopperCount);
                    config.set(path + ".updatableCount", data.updatableCount);
                    config.set(path + ".lightSources", data.lightSources);
                    config.set(path + ".score", data.score);
                    config.set(path + ".isLagMachine", data.isLagMachine);
                }
            }
            try {
                config.save(new File(plugin.getDataFolder(), "cache.yml"));
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save cache to disk: " + e.getMessage());
            }
        }

        public MutableChunkData getMutableChunkData(String worldName, int x, int z) {
            Map<String, MutableChunkData> worldCache = cache.get(worldName);
            if (worldCache == null) {
                return null;
            }
            String key = x + "," + z;
            return worldCache.get(key);
        }

        public CachedChunkData getCachedChunkData(String worldName, int x, int z) {
            MutableChunkData data = getMutableChunkData(worldName, x, z);
            return data != null ? data.toImmutable() : null;
        }

        public void updateChunkData(ChunkData data) {
            String worldName = data.world.getName();
            String key = data.chunkX + "," + data.chunkZ;
            MutableChunkData mutableData = new MutableChunkData();

            mutableData.entityCount = data.entityCount;
            mutableData.playerCount = data.playerCount;
            mutableData.tileEntityCount = data.tileEntityCount;
            mutableData.armorStandCount = data.armorStandCount;
            mutableData.redstoneCount = data.redstoneCount;
            mutableData.hopperCount = data.hopperCount;
            mutableData.updatableCount = data.updatableCount;
            mutableData.lightSources = data.lightSources;
            mutableData.score = data.score;
            mutableData.isLagMachine = data.isLagMachine;

            cache.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>()).put(key, mutableData);
        }
    }

    private record ChunkLocation(String world, int x, int z) {

        @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                ChunkLocation that = (ChunkLocation) o;
                return x == that.x && z == that.z && world.equals(that.world);
            }

    }
}