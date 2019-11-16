package com.lovetropics.donations;

import java.io.File;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import com.google.common.base.Charsets;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.ReactionAddEvent;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.MessageChannel;
import discord4j.core.object.entity.PrivateChannel;
import discord4j.core.object.entity.TextChannel;
import discord4j.core.object.entity.User;
import discord4j.core.object.reaction.ReactionEmoji;
import discord4j.core.object.util.Snowflake;
import discord4j.rest.http.client.ClientException;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.Value;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@RequiredArgsConstructor
@Slf4j
public class LoveTropicsListener {
    
    @Value
    private static class Donation {
        int id;
        double amount;
        @SerializedName("display_name")
        String name;
        String email;
    }
    
    private enum State {
        NONE,
        REJECTED,
        PENDING,
        VERIFIED,
        ACCEPTED,
        WHITELISTED_JAVA,
        WHITELISTED_BEDROCK,
        ;
    }
    
    @Value
    @RequiredArgsConstructor
    private static class Data {
        @NonFinal
        @Setter
        volatile Snowflake message;
        Map<Snowflake, State> userStates = Maps.newConcurrentMap();
        Map<Snowflake, String> verifiedEmails = Maps.newConcurrentMap();
        Map<Snowflake, Set<String>> attemptedEmails = Maps.newConcurrentMap();
        Map<Snowflake, Integer> resets = Maps.newConcurrentMap();
    }

    private static final Pattern MAYBE_EMAIL = Pattern.compile("\\S+@\\S+\\.\\w+");
    
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Snowflake.class, new SnowflakeTypeAdapter())
            .enableComplexMapKeySerialization()
            .create();
    
    private static final NumberFormat CURRENCY_FMT = NumberFormat.getCurrencyInstance(Locale.US);
    
    private final SaveHelper<Data> saveHelper = new SaveHelper<>(new File("lovetropics"), GSON, new Data());
    
    private final Data data = saveHelper.fromJson("data.json", Data.class);
    
    private final Snowflake guild = Snowflake.of(444746940761243652L); // Love Tropics
    private final Snowflake verifyChannel = Snowflake.of(642421786247430194L); // #verify-donation
    private final Snowflake adminRole = Snowflake.of(473430258347933707L); // Overseer
    private final Snowflake donorRole = Snowflake.of(641706857706160128L); // Donor
    private final Snowflake whitelistRole = Snowflake.of(642422058973659147L); // Server Member
    private final Snowflake whitelistChannel = Snowflake.of(644975720904392705L); // #java-relay
    
    private final String api;
    private final String key;
    private final int minDonation;
    private final String bedrockWhitelist;
    
    public Mono<Void> onMessage(MessageCreateEvent event) {
        return onMessageInternal(event)
                .then()
                .doOnError(t -> log.error("LoveTropics error: ", t))
                .onErrorResume(ClientException.class, t -> event.getMessage().getChannel()
                        .flatMap(c -> c.createMessage("Discord error processing donations: " + t.getMessage()))
                        .then())
                .onErrorResume(t -> event.getMessage().getChannel()
                        .flatMap(c -> c.createMessage("Unexpected error processing donations: " + t.toString()))
                        .then());
    }
    
    private Mono<?> onMessageInternal(MessageCreateEvent event) {
        Snowflake author = event.getMessage().getAuthor().map(User::getId).orElse(null);
        MessageChannel channel = event.getMessage().getChannel().block();
        if (channel instanceof PrivateChannel) {
            PrivateChannel dm = (PrivateChannel) channel;
            State state = data.getUserStates().getOrDefault(author, State.NONE);
            if (state == State.PENDING || state == State.VERIFIED) {
                final String email;
                int triesTmp = -1; // Where this is printed will never run if it's not set later on
                if (state == State.PENDING) {
                    email = event.getMessage().getContent().orElse("").trim();
                    if (MAYBE_EMAIL.matcher(email).matches()) {
                        Set<String> prevEmails = data.getAttemptedEmails().computeIfAbsent(author, $ -> Sets.newConcurrentHashSet());
                        triesTmp = data.getResets().merge(author, prevEmails.contains(email) ? 0 : 1, (i1, i2) -> Math.min(999, i1 + i2));
                        if (triesTmp > 3 && !prevEmails.contains(email)) {
                            return save().then(dm.createMessage("Sorry, you are out of email attempts."));
                        }
                        if (triesTmp < 100) { // In case of spammer...that's enough
                            data.getAttemptedEmails().get(author).add(email);
                        }
                    } else {
                        return dm.createMessage("That doesn't look like a valid email. Please try again.");
                    }
                } else {
                    email = data.getVerifiedEmails().get(author);
                }

                final int tries = triesTmp;
                return getTotalDonations(dm, email)
                        .filter(total -> total > 0)
                        .flatMap(total -> Mono.justOrEmpty(event.getMessage().getAuthor()).flatMap(u -> u.asMember(guild)).flatMap(m -> m.addRole(donorRole)).thenReturn(total))
                        .flatMap(total -> dm.createMessage("Your email was verified! Donation amount: " + CURRENCY_FMT.format(total)).thenReturn(total))
                        .flatMap(total -> {
                             data.getVerifiedEmails().put(author, email);
                             if (total >= minDonation) {
                                 data.getUserStates().put(author, State.ACCEPTED);
                                 return save().then(dm.createMessage("Congratulations! This amount qualifies for server access. Reply with your Minecraft ***Java Edition*** in-game name to be whitelisted. If you do not have or want java edition access, reply with \"none\"."));
                             } else {
                                 data.getUserStates().put(author, State.VERIFIED);
                                 return save().then(dm.createMessage("Unfortunately, this is not enough to qualify for server access. However, you have still been assigned the donor role!\n\nYou need at least " + CURRENCY_FMT.format(minDonation) + " across all donations to qualify.\n**Say anything in this chat to try again.**"));
                             }
                        })
                        .switchIfEmpty(dm.createMessage("Sorry, there were no donations by that email. Either the email was incorrect, or you have not donated yet.\n\nYou may try **" + (3 - tries) + "** more times to enter the correct email, or enter the same email again to re-attempt."));
                
            } else if (state == State.ACCEPTED) {
                String username = event.getMessage().getContent().orElse("").trim();
                if (username.equals("none")) {
                    data.getUserStates().put(author, State.WHITELISTED_JAVA);
                    return save().then(dm.createMessage("Skipped whitelisting for java edition. Please send your bedrock edition username now."));
                }
                return getUUID(username)
                    .flatMap(uuid -> Mono.justOrEmpty(event.getMessage().getAuthor()).flatMap(u -> u.asMember(guild)).flatMap(m -> m.addRole(whitelistRole)).thenReturn(uuid))
                    .flatMap(uuid -> event.getClient().getChannelById(whitelistChannel).cast(TextChannel.class).flatMap(c -> c.createMessage("!whitelist add " + username)).thenReturn(uuid))
                    .doOnNext($ -> data.getUserStates().put(author, State.WHITELISTED_JAVA))
                    .flatMap(this::thenSave)
                    .flatMap(uuid -> dm.createMessage("Whitelisted `" + username + "` on Java edition server.\nPlease send your bedrock edition username if you would like to be whitelisted there as well. You can ignore this message if not.\n\nHave fun!"))
                    .switchIfEmpty(dm.createMessage("That does not appear to be a valid Minecraft account name. Try again?"));
            } else if (state == State.WHITELISTED_JAVA) {
                String username = event.getMessage().getContent().orElse("").trim();
                return addBedrockWhitelist(username).thenReturn(username)
                        .flatMap(name -> Mono.justOrEmpty(event.getMessage().getAuthor()).flatMap(u -> u.asMember(guild)).flatMap(m -> m.addRole(whitelistRole)).thenReturn(name))
                        .doOnNext($ -> data.getUserStates().put(author, State.WHITELISTED_BEDROCK))
                        .flatMap(this::thenSave)
                        .flatMap(name -> dm.createMessage("Whitelisted `" + name + "` on Bedrock edition server.\n\nHave fun!"));
            }
        } else if (channel instanceof TextChannel) {
            Set<Snowflake> roles = event.getMember().map(m -> m.getRoleIds()).orElse(Collections.emptySet());
            if (channel.getId().equals(verifyChannel) && roles.contains(adminRole)) {
                if (event.getMessage().getContent().orElse("").equals("refresh")) {
                    return channel.getMessagesBefore(Snowflake.of(Instant.now()))
                            .timeout(Duration.ofSeconds(30))
                            .flatMap(Message::delete)
                            .then(channel.createMessage("React to this message to verify your donation and get your roles/whitelist."))
                            .doOnNext(m -> data.setMessage(m.getId()))
                            .doOnNext($ -> save())
                            .flatMap(m -> m.addReaction(ReactionEmoji.unicode("\uD83D\uDCB8")))
                            .onErrorResume(TimeoutException.class, e -> channel.createMessage("Sorry, the message history in this channel is too long, or otherwise took too long to load.").then());
                }
            }
        }
        return Mono.empty();
    }
    
    private Mono<Double> getTotalDonations(PrivateChannel channel, String email) {
        return HttpClient.create()
                .baseUrl(api)
                .headers(h -> h.add("Authorization", "Bearer " + key))
                .wiretap(true)
                .get()
                .uri("/donation/total/" + email)
                .responseSingle((resp, content) -> resp.status() == HttpResponseStatus.OK ? content.asString() : Mono.empty())
                .map(s -> GSON.fromJson(s, JsonObject.class))
                .map(json -> json.getAsJsonObject().getAsJsonObject("data").get("total").getAsDouble())
                .defaultIfEmpty(0.0);
    }
    
    public Mono<ReactionAddEvent> onReactAdd(ReactionAddEvent event) {
        if (event.getMessageId().equals(data.getMessage()) && !event.getUserId().equals(event.getClient().getSelfId().orElse(null)) && !data.getUserStates().containsKey(event.getUserId())) {
            return event.getUser().flatMap(u -> u.getPrivateChannel()
                        .doOnError(t -> log.error("Could not get private channel", t))
                        .onErrorResume($ -> Mono.empty()))
                    .doOnNext($ -> data.getUserStates().put(event.getUserId(), State.PENDING))
                    .flatMap(this::thenSave)
                    .flatMap(pm -> pm.createMessage("To verify your donation, please reply with the email you used to donate."))
                    .thenReturn(event);
        }
        return Mono.just(event);
    }
    
    private final HttpClient MOJANG_API = HttpClient.create()
            .baseUrl("https://api.mojang.com/users/profiles/minecraft/");
    
    private Mono<UUID> getUUID(String username) {
        return MOJANG_API.get().uri("/" + username + "?at=" + Instant.now().getEpochSecond()).responseSingle((resp, body) -> {
           if (resp.status() == HttpResponseStatus.OK) {
               return body.asString(Charsets.UTF_8)
                       .map(s -> GSON.fromJson(s, JsonObject.class))
                       .map(obj -> obj.get("id").getAsString())
                       .map(this::parseUUID);
           }
           return Mono.empty();
        });
    }
    
    private UUID parseUUID(String uuid) {
        return UUID.fromString(uuid.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }
    
    private Mono<Void> addBedrockWhitelist(String username) {
        return Mono.fromCallable(() -> {
            Process process = new ProcessBuilder("bash", "-c", "tmux send-keys -t \"0:Bedrock Server\" Enter "
                          + "\"whitelist add " + username + "\" Enter "
                          + "\"whitelist reload\" Enter")
                    .start();

            Mono.when(
                    Mono.fromRunnable(new StreamGobbler(process.getInputStream(), log::info)), 
                    Mono.fromRunnable(new StreamGobbler(process.getErrorStream(), log::error)))
            .subscribe();
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("Whitelist process returned exit code " + exitCode);
            }
            return null;
        }).then();
    }

    private Mono<Void> save() {
        return Mono.fromRunnable(() -> saveHelper.writeJson("data.json", data));
    }
    
    private <T> Mono<T> thenSave(T val) {
        return save().thenReturn(val);
    }
}