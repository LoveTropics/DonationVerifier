package com.lovetropics.donations;

import java.util.Scanner;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.ReactionAddEvent;
import discord4j.core.shard.GatewayBootstrap;
import discord4j.gateway.GatewayOptions;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class DonationVerifier {
    
    private static class Arguments {
        @Parameter(names = { "-a", "--auth" }, description = "The Discord app key to authenticate with.", required = true)
        private String authKey;
        
        @Parameter(names = "--ltapi", description = "URL which hosts the donation API", required = true) 
        private String loveTropicsApi;
        
        @Parameter(names = "--ltkey", description = "Authorization token to access the donation API", required = true)
        private String loveTropicsKey;

        @Parameter(names = "--mindonation", description = "Minimum donation to be whitelisted, default = 25")
        private int minDonation = 25;
    }
    
    private static Arguments args;
    
    public static void main(String[] argv) {        
        args = new Arguments();
        JCommander.newBuilder().addObject(args).build().parse(argv);
        
        final LoveTropicsListener ltListener = new LoveTropicsListener(args.loveTropicsApi, args.loveTropicsKey, args.minDonation);

        DiscordClient client = DiscordClientBuilder.create(args.authKey)
                .build();

        GatewayBootstrap<GatewayOptions> gateway = client.gateway()
                .setEnabledIntents(IntentSet.of(Intent.GUILD_MESSAGE_REACTIONS, Intent.DIRECT_MESSAGES, Intent.DIRECT_MESSAGE_REACTIONS))
                .withEventDispatcher(disp -> {
                    Mono<Void> reactions = disp.on(ReactionAddEvent.class)
                            .flatMap(ltListener::onReactAdd)
                            .then();
                    
                    Mono<Void> messages = disp.on(MessageCreateEvent.class)
                            .flatMap(ltListener::onMessage)
                            .then();

                    return Mono.when(reactions, messages);
                });

        
        // Handle "stop" and any future commands
        Mono.fromCallable(() -> {
            Scanner scan = new Scanner(System.in);
            while (true) {
                while (scan.hasNextLine()) {
                    if (scan.nextLine().equals("stop")) {
                        scan.close();
                        System.exit(0);
                    }
                }
                Thread.sleep(100);
            }
        }).subscribeOn(Schedulers.newSingle("Console Listener"))
          .subscribe();

        gateway.login()
            .doOnNext(g -> {
                // Make sure shutdown things are run, regardless of where shutdown came from
                // The above System.exit(0) will trigger this hook
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    g.logout().block();
                }));
            }).flatMap(g -> g.onDisconnect())
            .block();
    }
}
