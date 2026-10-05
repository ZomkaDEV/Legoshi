package dev.zomka.legoshi;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Links {

    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("legoshi-links.json");
    private static final Gson GSON = new Gson();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRTUVWXYZ2346789";
    private static final long TTL_MS = 5 * 60 * 1000;
    private static final SecureRandom RNG = new SecureRandom();

    private record Pending(UUID uuid, long expires) {}

    private static final Map<String, Pending> pending = new HashMap<>();
    private static final Map<String, String> linked = load();

    private Links() {}

    private static Map<String, String> load() {
        try {
            if (Files.exists(PATH)) {
                Map<String, String> m = GSON.fromJson(Files.readString(PATH), new TypeToken<Map<String, String>>() {}.getType());
                if (m != null) return m;
            }
        } catch (IOException | RuntimeException e) {
            Legoshi.LOGGER.error("Failed to read {}", PATH, e);
        }
        return new HashMap<>();
    }

    public static synchronized boolean isLinked(UUID uuid) {
        return linked.containsKey(uuid.toString());
    }

    public static synchronized String getLinked(UUID uuid) {
        return linked.get(uuid.toString());
    }

    public static synchronized String newCode(UUID uuid) {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> p.expires < now || p.uuid.equals(uuid));
        String code;
        do {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
            code = sb.toString();
        } while (pending.containsKey(code));
        pending.put(code, new Pending(uuid, now + TTL_MS));
        return code;
    }

    public static synchronized String redeem(String code, String discordId) {
        if (linked.containsValue(discordId)) return "Your Discord account is already linked to a Minecraft account. Please contact <@253154276560338945> for help.";
        Pending p = pending.remove(code.trim().toUpperCase());
        if (code.length() != 6 || p == null || p.expires < System.currentTimeMillis()) return "Invalid or expired code. Join the server again to get a new code.";
        linked.put(p.uuid.toString(), discordId);
        try {
            Files.writeString(PATH, GSON.toJson(linked));
        } catch (IOException e) {
            Legoshi.LOGGER.error("Failed to write {}", PATH, e);
        }
        return null;
    }
}
