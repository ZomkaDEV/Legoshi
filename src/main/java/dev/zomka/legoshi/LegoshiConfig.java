package dev.zomka.legoshi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LegoshiConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("legoshi.json");

    public String token = "";
    public String channelId = "";
    public String webhookUrl = "";

    public static LegoshiConfig load() {
        if (Files.exists(PATH)) {
            try {
                return GSON.fromJson(Files.readString(PATH), LegoshiConfig.class);
            } catch (IOException | RuntimeException e) {
                Legoshi.LOGGER.error("Failed to read {}", PATH, e);
            }
        }
        LegoshiConfig config = new LegoshiConfig();
        try {
            Files.writeString(PATH, GSON.toJson(config));
        } catch (IOException e) {
            Legoshi.LOGGER.error("Failed to write default config to {}", PATH, e);
        }
        return config;
    }
}
