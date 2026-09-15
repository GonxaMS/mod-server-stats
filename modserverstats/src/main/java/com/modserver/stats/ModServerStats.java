package com.modserver.stats;

import com.mojang.logging.LogUtils;
import com.modserver.stats.config.ServerStatsConfig;
import com.modserver.stats.service.ServerStatsService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod(ModServerStats.MOD_ID)
public final class ModServerStats {
    public static final String MOD_ID = "modserverstats";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ModServerStats(IEventBus modEventBus, ModContainer modContainer) {
        prepareConfigLayout();
        modContainer.registerConfig(ModConfig.Type.COMMON, ServerStatsConfig.SPEC,
                "modserverstats/common.toml");
        NeoForge.EVENT_BUS.register(new ServerStatsService());
        LOGGER.info("Mod Server Stats loaded");
    }

    private static void prepareConfigLayout() {
        Path config = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
        Path modConfig = config.resolve("modserverstats");
        try {
            Files.createDirectories(modConfig);
            moveIfNeeded(config.resolve("modserverstats-common.toml"),
                    modConfig.resolve("common.toml"));
            moveIfNeeded(config.resolve("modserverstats-history"),
                    modConfig.resolve("history"));
        } catch (IOException error) {
            LOGGER.warn("Could not organize mod files under config/modserverstats: {}", error.getMessage());
        }
    }

    private static void moveIfNeeded(Path source, Path destination) throws IOException {
        if (!Files.exists(source) || Files.exists(destination)) return;
        Files.move(source, destination);
    }
}
