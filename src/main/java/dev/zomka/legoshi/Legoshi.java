package dev.zomka.legoshi;

import com.google.gson.Gson;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.EmbedType;
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
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

import static java.awt.SystemColor.text;

public class Legoshi implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("legoshi");
    private static volatile MinecraftServer server;
    private static volatile JDA jda;
    private static volatile TextChannel channel;
    private static volatile String webhookUrl;
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final Gson gson = new Gson();

    private record Profile(String name, String avatarUrl, int color) {}
    private static final java.util.Map<java.util.UUID, Profile> profiles = new java.util.concurrent.ConcurrentHashMap<>();

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
                handler.disconnect(Component.literal("You need to link your Discord account to join the server!").withStyle(ChatFormatting.BOLD).append(Component.literal("\n\nRun /link " + Links.newCode(uuid) + " in the Discord server. The code is valid for 5 minutes.").withStyle(ChatFormatting.RESET)));
                return;
            }
            channel.getGuild().retrieveMemberById(Links.getLinked(uuid)).queue(
                    m -> {
                        profiles.put(uuid, new Profile(m.getEffectiveName(), m.getEffectiveAvatarUrl(), m.getColors().getPrimaryRaw()));
                        embed(":wave: **" + m.getEffectiveName() + " joined the server!**", 0x57F287);
                        updateTopic(server.getPlayerList().getPlayerCount());
                    },
                    e -> LOGGER.warn("Failed to fetch Discord profile for {}", uuid, e));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            Profile p = profiles.remove(handler.getPlayer().getUUID());
            if (p == null) return;
            embed("<:AAA2:1530210412795269242> **" + p.name() + " left the server...**", 0xED4245);
            updateTopic(server.getPlayerList().getPlayerCount() - 1);
        });

        ServerMessageEvents.GAME_MESSAGE.register((srv, message, overlay) -> {
            if (channel == null || !(message.getContents() instanceof TranslatableContents tc)) return;
            String emoji = switch (tc.getKey()) {
                case "chat.type.advancement.task" -> ":medal:";
                case "chat.type.advancement.goal" -> ":dart:";
                case "chat.type.advancement.challenge" -> ":partying_face:";
                default -> null;
            };
            if (emoji == null) return;
            String out = emoji + " **" + message.getString() + "**";
            if (tc.getArgs().length > 1 && tc.getArgs()[1] instanceof Component adv) {
                String tooltip = adv.visit((style, str) -> style.getHoverEvent() instanceof HoverEvent.ShowText h
                        ? java.util.Optional.of(h.value().getString()) : java.util.Optional.<String>empty(), Style.EMPTY).orElse("");
                String[] parts = tooltip.split("\n", 2);
                if (parts.length == 2) out += "\n*" + parts[1] + "*";
            }
            embed(out, emoji.equals(":partying_face:") ? 0xFEE75C : 0x5865F2);
        });

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            Profile profile = profiles.get(sender.getUUID());
            var payload = java.util.Map.of(
                    "content", message.decoratedContent().getString(),
                    "username", profile.name(),
                    "avatar_url", profile.avatarUrl());
            try {
                String jsonPayload = gson.toJson(payload);

                MutableComponent formattedName = Component.literal(profile.name()).withStyle(Style.EMPTY
                        .withColor(profile.color())
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(sender.getGameProfile().name()))));

                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("[MC] ").withStyle(ChatFormatting.GREEN)
                                .append(formattedName)
                                .append(Component.literal(": ").withStyle(ChatFormatting.WHITE)
                                .append(message.decoratedContent())),
                        false);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();

                client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenAccept(response -> {
                            if (response.statusCode() >= 400) {
                                LOGGER.error("Failed to send webhook: status {}", response.statusCode());
                            }
                        });
            } catch (Exception e) {
                channel.sendMessage("<@253154276560338945> Failed to send message or webhook! Check logs.").queue();
                e.printStackTrace();
            }
            return false;
        });

        start(config); // blocking on purpose: no bot, no whitelist, no server
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (jda != null) {
                channel.sendMessageEmbeds(new EmbedBuilder().setTitle("Server is shutting down...").setColor(0xED4245).build()).queue();
                channel.getManager().setTopic("The server is currently down.").queue();
                jda.shutdown();
            }
        });
    }

    private static void embed(String text, int color) {
        channel.sendMessageEmbeds(new EmbedBuilder().setDescription(text).setColor(color).build()).queue();
    }

    private static void updateTopic(int players) {
        channel.getManager().setTopic("See pinned message in details " + players + (players == 1 ? " player" : " players") + " online").queue();
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
            channel.sendMessageEmbeds(new EmbedBuilder().setTitle("Server is online!").setColor(0x57F287).build()).queue();
            updateTopic(0);
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

        private static MutableComponent link(String label, String url) {
            return Component.literal(label).withStyle(Style.EMPTY
                    .withColor(ChatFormatting.AQUA)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
        }

        private static final java.util.regex.Pattern EMOJI = java.util.regex.Pattern.compile("<(a?):(\\w+):(\\d+)>");

        /** Plain text with custom Discord emojis turned into clickable :name: links to their image. */
        private static MutableComponent withEmojis(String text) {
            MutableComponent out = Component.empty();
            java.util.regex.Matcher m = EMOJI.matcher(text);
            int last = 0;
            while (m.find()) {
                out.append(Component.literal(text.substring(last, m.start())).withStyle(ChatFormatting.WHITE));
                String ext = m.group(1).isEmpty() ? "png" : "gif";
                out.append(link(":" + m.group(2) + ":", "https://cdn.discordapp.com/emojis/" + m.group(3) + "." + ext));
                last = m.end();
            }
            return out.append(Component.literal(text.substring(last)).withStyle(ChatFormatting.WHITE));
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

            MutableComponent line = Component.literal("[Discord] ").withStyle(ChatFormatting.BLUE)
                    .append(name)
                    .append(Component.literal(": ").withStyle(ChatFormatting.WHITE));
            String url = text.trim();
            boolean lone = url.matches("https?://\\S+");
            if (lone && (url.matches("(?i)[^?#]*\\.gif(?:[?#].*)?")
                    || event.getMessage().getEmbeds().stream().anyMatch(e -> e.getType() == EmbedType.GIFV))) {
                line.append(link("[GIF]", url));
            } else {
                line.append(withEmojis(text));
            }
            boolean needSpace = !text.isBlank();
            for (Message.Attachment a : event.getMessage().getAttachments()) {
                String label = "image/gif".equals(a.getContentType()) || a.getFileName().toLowerCase().endsWith(".gif") ? "[GIF]"
                        : a.isImage() ? "[Image]"
                        : a.isVideo() ? "[Video]"
                        : a.getFileName();
                if (needSpace) line.append(Component.literal(" "));
                needSpace = true;
                line.append(link(label, a.getUrl()));
            }
            Message ref = event.getMessage().getReferencedMessage();
            if (ref != null) {
                String refName = ref.getMember() != null ? ref.getMember().getEffectiveName() : ref.getAuthor().getName();
                String refText = ref.getContentDisplay().replaceAll("\\s+", " ").trim();
                if (refText.isEmpty()) refText = ref.getAttachments().isEmpty() ? "[embed]" : "[attachment]";
                if (refText.length() > 60) refText = refText.substring(0, 60) + "...";
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("    ┌──── " + refName + ": " + refText).withStyle(ChatFormatting.GRAY),
                        false);
            }
            server.getPlayerList().broadcastSystemMessage(line, false);
        }
    }
}