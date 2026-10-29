package com.dafeiyu.sharedinventory;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * 共享背包：同一存档内所有玩家共用 36 格主背包（快捷栏 + 背包）。
 *
 * <p>实现方式是“镜像 + 增量合并”：
 * 每个玩家仍然拥有自己的 Inventory 对象，但每 tick 会把自己的变化折算成
 * 增量写进服务端的共享容器，再把共享容器同步回所有人。这样不需要 Mixin，
 * 也不依赖客户端模组逻辑。</p>
 */
@Mod(SharedInventoryMod.MODID)
public final class SharedInventoryMod {
    public static final String MODID = "sharedinventory";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SharedInventoryMod(FMLJavaModLoadingContext context) {
        // 生命周期
        ServerStartedEvent.BUS.addListener(SharedInventoryManager::onServerStarted);
        ServerStoppingEvent.BUS.addListener(SharedInventoryManager::onServerStopping);

        // 每个服务端 tick 结束后同步一次
        TickEvent.ServerTickEvent.Post.BUS.addListener(SharedInventoryManager::onServerTick);

        // 玩家进出、重生、换维度时强制重新同步
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(SharedInventoryManager::onLogin);
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(SharedInventoryManager::onLogout);
        PlayerEvent.PlayerRespawnEvent.BUS.addListener(SharedInventoryManager::onRespawn);
        PlayerEvent.PlayerChangedDimensionEvent.BUS.addListener(SharedInventoryManager::onChangedDimension);

        // 玩家死亡时不掉落共享背包里的东西，避免“死了复制一份”。
        // EventBus 7 的 Cancellable 事件用 Predicate 表示“满足条件就取消”。
        LivingDropsEvent.BUS.addListener(SharedInventoryMod::shouldCancelPlayerDrops);
    }

    private static boolean shouldCancelPlayerDrops(LivingDropsEvent event) {
        return event.getEntity() instanceof ServerPlayer;
    }
}
