package com.dafeiyu.sharedinventory;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端共享背包管理器。
 *
 * <p>共享的是玩家背包的 0..35 格（快捷栏 0..8 + 主背包 9..35）。
 * 盔甲、副手、末影箱、合成格保持各自独立。</p>
 *
 * <p>同步策略：每个玩家上一次看到的共享内容保存在 {@code snapshots} 里。
 * 每 tick 对比“当前背包”和“上次看到的内容”，只把差异折算成增删写进共享容器；
 * 然后再把共享容器写回所有玩家。这样两个玩家同时操作时不会整包互相覆盖，
 * 但极端情况下（同一 tick 同时拖同一格）仍可能出现刷/吞物品，属于镜像方案的固有限制。</p>
 */
public final class SharedInventoryManager {
    /** 共享的主背包格数。 */
    public static final int SHARED_SLOTS = 36;
    /** 每 10 秒落盘一次（20 tick/秒）。 */
    private static final int SAVE_INTERVAL_TICKS = 200;

    private static SharedInventoryManager instance;

    private final MinecraftServer server;
    private final Path saveFile;

    /** 共享容器，索引与玩家 Inventory 的 0..35 完全对应。 */
    private final NonNullList<ItemStack> shared = NonNullList.withSize(SHARED_SLOTS, ItemStack.EMPTY);
    /** 每个玩家上一次同步后看到的共享内容。 */
    private final Map<UUID, NonNullList<ItemStack>> snapshots = new HashMap<>();
    /** 需要无条件覆盖的玩家（登录 / 重生 / 换维度）。 */
    private final Set<UUID> forceResync = new HashSet<>();

    private boolean initialized;
    private boolean dirty;
    private int ticksSinceSave;

    private SharedInventoryManager(MinecraftServer server) {
        this.server = server;
        this.saveFile = server.getWorldPath(LevelResource.ROOT).resolve("sharedinventory.dat");
    }

    // ------------------------------------------------------------------
    // Forge 事件入口
    // ------------------------------------------------------------------

    public static void onServerStarted(ServerStartedEvent event) {
        instance = new SharedInventoryManager(event.getServer());
        instance.load();
        SharedInventoryMod.LOGGER.info("[共享背包] 已启动，存档文件: {}", instance.saveFile);
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        if (instance != null) {
            instance.captureAllOnline();
            instance.save();
            instance = null;
        }
    }

    public static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        if (instance != null && instance.server == event.server()) {
            instance.tick();
        }
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (instance != null && event.getEntity() instanceof ServerPlayer player) {
            instance.forceResync.add(player.getUUID());
            instance.snapshots.remove(player.getUUID());
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (instance != null && event.getEntity() instanceof ServerPlayer player) {
            // 下线前把最后一刻的变化收进来，避免“刚捡起就退出”导致物品丢在个人背包里
            if (instance.initialized && player.containerMenu.getCarried().isEmpty()) {
                instance.capturePlayerChanges(player);
                instance.save();
            }
            instance.forceResync.remove(player.getUUID());
            instance.snapshots.remove(player.getUUID());
        }
    }

    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (instance != null && event.getEntity() instanceof ServerPlayer player) {
            instance.forceResync.add(player.getUUID());
            instance.snapshots.remove(player.getUUID());
        }
    }

    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (instance != null && event.getEntity() instanceof ServerPlayer player) {
            instance.forceResync.add(player.getUUID());
            instance.snapshots.remove(player.getUUID());
        }
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    private void tick() {
        if (!initialized && !tryInitializeFromFirstPlayerWithItems()) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // 玩家手里正拖着东西时，背包处于“半提交”状态，先不动他，
            // 等他放下/关界面后下一 tick 再统一处理，避免复制。
            if (!player.containerMenu.getCarried().isEmpty()) {
                continue;
            }

            UUID id = player.getUUID();
            boolean force = forceResync.remove(id) || !snapshots.containsKey(id);

            if (force) {
                applyTo(player);
                takeSnapshot(player);
                continue;
            }

            capturePlayerChanges(player);
        }

        // 把合并后的共享内容写回所有人（包括刚刚贡献了变化的玩家，用于纠正槽位差异）
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.containerMenu.getCarried().isEmpty()) {
                continue;
            }
            applyTo(player);
            takeSnapshot(player);
        }

        if (dirty) {
            ticksSinceSave++;
            if (ticksSinceSave >= SAVE_INTERVAL_TICKS) {
                save();
            }
        }
    }

    /**
     * 如果存档里还没有共享数据，就用第一个“背包里有东西”的玩家初始化。
     * 这样老存档第一次装模组时不会把第一个人身上的东西直接抹掉。
     */
    private boolean tryInitializeFromFirstPlayerWithItems() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (hasAnyItem(player)) {
                for (int i = 0; i < SHARED_SLOTS; i++) {
                    shared.set(i, player.getInventory().getItem(i).copy());
                }
                initialized = true;
                dirty = true;
                save();
                SharedInventoryMod.LOGGER.info(
                        "[共享背包] 使用玩家 {} 的背包完成初始化", player.getName().getString());
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyItem(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < SHARED_SLOTS; i++) {
            if (!inventory.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 差异采集
    // ------------------------------------------------------------------

    private void captureAllOnline() {
        if (!initialized) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu.getCarried().isEmpty()) {
                capturePlayerChanges(player);
            }
        }
    }

    private void capturePlayerChanges(ServerPlayer player) {
        NonNullList<ItemStack> last = snapshots.get(player.getUUID());
        if (last == null) {
            return;
        }

        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < SHARED_SLOTS; slot++) {
            ItemStack current = inventory.getItem(slot);
            ItemStack before = last.get(slot);

            if (ItemStack.matches(current, before)) {
                continue;
            }

            applyPlayerChange(slot, before, current, player);
            last.set(slot, current.copy());
        }
    }

    /**
     * 把一个格子的“之前 -> 现在”折算到共享容器上。
     */
    private void applyPlayerChange(int slot, ItemStack before, ItemStack after, ServerPlayer player) {
        if (before.isEmpty()) {
            addToShared(after.copy(), slot, player);
            return;
        }

        if (after.isEmpty()) {
            removeFromShared(before, slot, before.getCount());
            return;
        }

        if (ItemStack.isSameItemSameComponents(before, after)) {
            int delta = after.getCount() - before.getCount();
            if (delta > 0) {
                addToShared(after.copyWithCount(delta), slot, player);
            } else if (delta < 0) {
                removeFromShared(before, slot, -delta);
            }
            return;
        }

        // 物品变了（吃东西、用桶、装备损耗等）：先扣掉旧的，再加新的
        removeFromShared(before, slot, before.getCount());
        addToShared(after.copy(), slot, player);
    }

    // ------------------------------------------------------------------
    // 共享容器增删
    // ------------------------------------------------------------------

    private void addToShared(ItemStack stack, int preferredSlot, ServerPlayer player) {
        if (stack.isEmpty()) {
            return;
        }

        mergeInto(preferredSlot, stack);
        if (stack.isEmpty()) {
            return;
        }

        if (shared.get(preferredSlot).isEmpty()) {
            shared.set(preferredSlot, stack.copy());
            stack.setCount(0);
            dirty = true;
            return;
        }

        for (int i = 0; i < SHARED_SLOTS && !stack.isEmpty(); i++) {
            if (i != preferredSlot) {
                mergeInto(i, stack);
            }
        }
        if (stack.isEmpty()) {
            return;
        }

        for (int i = 0; i < SHARED_SLOTS && !stack.isEmpty(); i++) {
            if (shared.get(i).isEmpty()) {
                shared.set(i, stack.copy());
                stack.setCount(0);
                dirty = true;
                return;
            }
        }

        // 36 格真的满了：把多出来的掉在玩家脚下，而不是凭空删掉
        SharedInventoryMod.LOGGER.warn(
                "[共享背包] 共享背包已满，{} 的 {} 个 {} 掉落在脚下",
                player.getName().getString(), stack.getCount(), stack.getHoverName().getString());
        ItemEntity drop = new ItemEntity(
                player.level(), player.getX(), player.getY() + 0.5, player.getZ(), stack.copy());
        drop.setPickUpDelay(40);
        player.level().addFreshEntity(drop);
    }

    private void mergeInto(int slot, ItemStack stack) {
        ItemStack target = shared.get(slot);
        if (target.isEmpty() || !ItemStack.isSameItemSameComponents(target, stack)) {
            return;
        }
        int room = Math.min(target.getMaxStackSize(), stack.getMaxStackSize()) - target.getCount();
        int moved = Math.min(room, stack.getCount());
        if (moved > 0) {
            target.grow(moved);
            stack.shrink(moved);
            dirty = true;
        }
    }

    private void removeFromShared(ItemStack like, int preferredSlot, int amount) {
        int remaining = amount;
        remaining = removeFromSlot(preferredSlot, like, remaining);
        for (int i = 0; i < SHARED_SLOTS && remaining > 0; i++) {
            if (i != preferredSlot) {
                remaining = removeFromSlot(i, like, remaining);
            }
        }
        if (remaining > 0) {
            SharedInventoryMod.LOGGER.warn(
                    "[共享背包] 需要扣除 {} 个 {}，但共享背包里只找到 {} 个（可能已被其他玩家用掉）",
                    amount, like.getHoverName().getString(), amount - remaining);
        }
    }

    private int removeFromSlot(int slot, ItemStack like, int amount) {
        if (amount <= 0) {
            return 0;
        }
        ItemStack target = shared.get(slot);
        if (target.isEmpty() || !ItemStack.isSameItemSameComponents(target, like)) {
            return amount;
        }
        int taken = Math.min(amount, target.getCount());
        target.shrink(taken);
        if (target.isEmpty()) {
            shared.set(slot, ItemStack.EMPTY);
        }
        dirty = true;
        return amount - taken;
    }

    // ------------------------------------------------------------------
    // 写回玩家
    // ------------------------------------------------------------------

    /**
     * 把共享容器写进玩家背包，只改有差异的格子。
     */
    private void applyTo(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        boolean changed = false;

        for (int slot = 0; slot < SHARED_SLOTS; slot++) {
            ItemStack target = shared.get(slot);
            if (!ItemStack.matches(inventory.getItem(slot), target)) {
                inventory.setItem(slot, target.copy());
                changed = true;
            }
        }

        if (changed) {
            // 当前打开的界面（箱子/工作台）也要刷新玩家背包那几格
            player.inventoryMenu.broadcastChanges();
            if (player.containerMenu != player.inventoryMenu) {
                player.containerMenu.broadcastChanges();
            }
        }
    }

    private void takeSnapshot(ServerPlayer player) {
        NonNullList<ItemStack> snapshot = NonNullList.withSize(SHARED_SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < SHARED_SLOTS; i++) {
            snapshot.set(i, shared.get(i).copy());
        }
        snapshots.put(player.getUUID(), snapshot);
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    private void load() {
        if (!Files.exists(saveFile)) {
            return;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(saveFile, NbtAccounter.unlimitedHeap());
            ValueInput input = TagValueInput.create(
                    ProblemReporter.DISCARDING, server.registryAccess(), root);
            ContainerHelper.loadAllItems(input, shared);
            initialized = true;
            SharedInventoryMod.LOGGER.info("[共享背包] 已读取共享背包存档");
        } catch (Exception exception) {
            SharedInventoryMod.LOGGER.error("[共享背包] 读取存档失败，将重新初始化", exception);
        }
    }

    private void save() {
        if (!initialized) {
            return;
        }
        try {
            Files.createDirectories(saveFile.getParent());
            TagValueOutput output = TagValueOutput.createWithContext(
                    ProblemReporter.DISCARDING, server.registryAccess());
            ContainerHelper.saveAllItems(output, shared);
            NbtIo.writeCompressed(output.buildResult(), saveFile);
            dirty = false;
            ticksSinceSave = 0;
        } catch (IOException exception) {
            SharedInventoryMod.LOGGER.error("[共享背包] 保存存档失败", exception);
        }
    }
}
