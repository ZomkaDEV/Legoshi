package dev.zomka.legoshi;

import com.google.gson.Gson;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

public class Legoshi implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("legoshi");
    private static volatile MinecraftServer server;
    private static volatile JDA jda;
    private static volatile TextChannel channel;
    private static volatile String webhookUrl;
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final Gson gson = new Gson();

    @Override
    public void onInitialize() {
        LegoshiConfig config = LegoshiConfig.load();
        if (config.token == null || config.token.isBlank() || config.webhookUrl == null || config.webhookUrl.isBlank()) {
            throw new IllegalStateException("No bot token or webhook set in config/legoshi.json");
        }

        webhookUrl = config.webhookUrl;

        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            var uuid = handler.getPlayer().getUUID();
            if (!Links.isLinked(uuid)) {
                handler.disconnect(Component.literal("You need to link your Discord account to join the server!\n\nRun /link " + Links.newCode(uuid) + " in the Discord server. The code is valid for 5 minutes."));
            }
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            var payload = java.util.Map.of(
                    "content", message.decoratedContent().getString(),
                    "username", sender.getGameProfile().name(),
                    "avatar_url", "https://api.creepernation.net/head/" + sender.getStringUUID());
            try {
                String jsonPayload = gson.toJson(payload);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();

                client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenAccept(response -> {
                            if (response.statusCode() >= 400) {
                                LOGGER.warn("Failed to send webhook: status {}", response.statusCode());
                            }
                        });
            } catch (Exception e) {
                channel.sendMessage("<@253154276560338945> Failed to send webhook! Check logs.").queue();
                e.printStackTrace();
            }
        });

        Thread.ofPlatform().name("Legoshi-Bot").start(() -> start(config));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (jda != null) {
                channel.sendMessage("Server is shutting down...").queue();
                jda.shutdown();
            }
        });
    }

    private void start(LegoshiConfig config) {
        try {
            jda = JDABuilder.createLight(config.token,
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT,
                            GatewayIntent.GUILD_MEMBERS)
                    .addEventListeners(new MessageListener(config.channelId))
                    .build().awaitReady();
            channel = jda.getTextChannelById(config.channelId);
            if (channel == null) {
                throw new IllegalStateException("Channel " + config.channelId + " not found, check channelId in config/legoshi.json");
            }
            channel.getGuild().upsertCommand(Commands.slash("link", "Link your Minecraft account")
                    .addOption(OptionType.STRING, "code", "Code shown when you were kicked", true))
                    .queue(c -> LOGGER.info("Registered /link"),
                            e -> LOGGER.error("Failed to register /link (does the bot have the applications.commands scope?)", e));
            channel.sendMessage("Server is online!").queue();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start Discord bot", e);
        }
    }

    public class MessageListener extends ListenerAdapter {

        private final String channelId;

        public MessageListener(String channelId) {
            this.channelId = channelId;
        }

        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            if (event.getName().equals("link")) {
                String error = Links.redeem(event.getOption("code").getAsString(), event.getUser().getId());
                event.reply(error == null ? "Linked! You can join now." : error).setEphemeral(true).queue();
            }
        }

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) return;
            if (!event.getChannel().getId().equals(channelId)) return;

            String text = event.getMessage().getContentRaw();
            Member member = event.getMember();
            MutableComponent name = Component.literal(member.getEffectiveName());
            if (member != null) {
                name.withColor(member.getColors().getPrimaryRaw());
            }

            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("[Discord] ").withStyle(ChatFormatting.BLUE)
                            .append(name)
                            .append(Component.literal(": " + text).withStyle(ChatFormatting.WHITE)),
                    false);
        }
    }
}
