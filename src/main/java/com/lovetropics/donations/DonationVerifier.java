package com.lovetropics.donations;

import java.util.Scanner;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.ReactionAddEvent;
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
        
        final LoveTropicsListener ltListener = new LoveTropicsListener(args.loveTropicsApi, args.loveTropicsKey, 25);

        DiscordClient client = new DiscordClientBuilder(args.authKey)
                .build();
        
        // Make sure shutdown things are run, regardless of where shutdown came from
        // The above System.exit(0) will trigger this hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            client.logout().block();
        }));
                
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
        
        Mono<Void> reactions = client.getEventDispatcher().on(ReactionAddEvent.class)
                .flatMap(ltListener::onReactAdd)
                .then();
        
        Mono<Void> messages = client.getEventDispatcher().on(MessageCreateEvent.class)
                .flatMap(ltListener::onMessage)
                .then();
        
        Mono.when(reactions, messages, client.login()).block();
    }
}
