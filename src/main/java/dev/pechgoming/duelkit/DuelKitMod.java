
package dev.pechhoming.duelkit;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

import static net.minecraft.server.command.CommandManager.*;

public class DuelKitMod implements ModInitializer {

    private static final Map<String, List<ItemStack>> KITS =
            new LinkedHashMap<>();

    private static final Map<UUID, DuelRequest> REQUESTS =
            new HashMap<>();

    private static final Map<UUID, DuelSession> DUELS =
            new HashMap<>();

    @Override
    public void onInitialize() {

        registerCommands();

        ServerTickEvents.END_SERVER_TICK.register(
                DuelKitMod::tickDuels
        );

        ServerLivingEntityEvents.AFTER_DEATH.register(
                DuelKitMod::onPlayerDeath
        );

        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) ->
                        handlePlayerDisconnect(
                                handler.getPlayer()
                        )
        );

        System.out.println("[DuelKit] Loaded!");
    }

    // COMMANDS

    private void registerCommands() {

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, environment) -> {

            dispatcher.register(literal("pvp")
                .requires(source ->
                        source.hasPermissionLevel(2)
                )
                .then(literal("kit")

                    .executes(ctx -> {
                        ServerPlayerEntity p =
                                ctx.getSource().getPlayerOrThrow();

                        p.sendMessage(
                                Text.literal("§6§lDUEL KIT"),
                                false
                        );

                        p.sendMessage(
                                Text.literal("/pvp kit create <name>"),
                                false
                        );

                        p.sendMessage(
                                Text.literal("/pvp kit list"),
                                false
                        );

                        p.sendMessage(
                                Text.literal("/pvp kit delete <name>"),
                                false
                        );

                        return 1;
                    })

                    .then(literal("create")
                        .then(argument(
                                "name",
                                StringArgumentType.word()
                        )
                        .executes(ctx ->
                                saveKit(
                                    ctx.getSource().getPlayerOrThrow(),
                                    StringArgumentType.getString(
                                            ctx, "name"
                                    )
                                )
                        ))
                    )

                    .then(literal("list")
                        .executes(ctx ->
                                listKits(
                                    ctx.getSource().getPlayerOrThrow()
                                )
                        )
                    )

                    .then(literal("delete")
                        .then(argument(
                                "name",
                                StringArgumentType.word()
                        )
                        .executes(ctx ->
                                deleteKit(
                                    ctx.getSource().getPlayerOrThrow(),
                                    StringArgumentType.getString(
                                            ctx, "name"
                                    )
                                )
                        ))
                    )
                )
            );

            dispatcher.register(literal("duel")

                .then(literal("accept")
                    .executes(ctx ->
                            acceptDuel(
                                ctx.getSource().getPlayerOrThrow()
                            )
                    )
                )

                .then(literal("deny")
                    .executes(ctx ->
                            denyDuel(
                                ctx.getSource().getPlayerOrThrow()
                            )
                    )
                )

                .then(argument(
                        "player",
                        StringArgumentType.word()
                )
                .executes(ctx ->
                        requestDuel(
                            ctx.getSource().getPlayerOrThrow(),
                            StringArgumentType.getString(
                                    ctx, "player"
                            ),
                            null
                        )
                )
                .then(argument(
                        "kit",
                        StringArgumentType.word()
                )
                .executes(ctx ->
                        requestDuel(
                            ctx.getSource().getPlayerOrThrow(),
                            StringArgumentType.getString(
                                    ctx, "player"
                            ),
                            StringArgumentType.getString(
                                    ctx, "kit"
                            )
                        )
                )))
            );
        });
    }

    // SAVE KIT

    private static int saveKit(
            ServerPlayerEntity player,
            String name
    ) {

        List<ItemStack> items = new ArrayList<>();

        for (int i = 0;
             i < player.getInventory().size();
             i++) {

            items.add(
                    player.getInventory()
                            .getStack(i)
                            .copy()
            );
        }

        KITS.put(
                name.toLowerCase(Locale.ROOT),
                items
        );

        player.sendMessage(
                Text.literal("Kit saved: " + name)
                        .formatted(Formatting.GREEN),
                false
        );

        return 1;
    }

    // LIST KITS

    private static int listKits(ServerPlayerEntity player) {

        if (KITS.isEmpty()) {

            player.sendMessage(
                    Text.literal("No kits available."),
                    false
            );

            return 1;
        }

        player.sendMessage(
                Text.literal(
                        "Available kits: " +
                                String.join(", ", KITS.keySet())
                ).formatted(Formatting.GOLD),
                false
        );

        return 1;
    }

    // DELETE KIT

    private static int deleteKit(
            ServerPlayerEntity player,
            String name
    ) {

        if (KITS.remove(
                name.toLowerCase(Locale.ROOT)
        ) == null) {

            player.sendMessage(
                    Text.literal("Kit not found."),
                    false
            );

        } else {

            player.sendMessage(
                    Text.literal("Kit deleted.")
                            .formatted(Formatting.YELLOW),
                    false
            );
        }

        return 1;
    }

    // DUEL REQUEST

    private static int requestDuel(
            ServerPlayerEntity sender,
            String targetName,
            String kitName
    ) {

        ServerPlayerEntity target =
                sender.getServer()
                        .getPlayerManager()
                        .getPlayer(targetName);

        if (target == null || target == sender) {

            sender.sendMessage(
                    Text.literal("Player not found."),
                    false
            );

            return 0;
        }

        if (sender.getWorld() != target.getWorld()) {

            sender.sendMessage(
                    Text.literal("Players must be in the same dimension."),
                    false
            );

            return 0;
        }

        if (DUELS.containsKey(sender.getUuid()) ||
                DUELS.containsKey(target.getUuid())) {

            sender.sendMessage(
                    Text.literal("A player is already in a duel."),
                    false
            );

            return 0;
        }

        if (KITS.isEmpty()) {

            sender.sendMessage(
                    Text.literal("No kits available."),
                    false
            );

            return 0;
        }

        String selectedKit = kitName == null
                ? KITS.keySet().iterator().next()
                : kitName.toLowerCase(Locale.ROOT);

        if (!KITS.containsKey(selectedKit)) {

            sender.sendMessage(
                    Text.literal("Kit not found."),
                    false
            );

            return 0;
        }

        REQUESTS.put(
                target.getUuid(),
                new DuelRequest(
                        sender.getUuid(),
                        selectedKit
                )
        );

        sender.sendMessage(
                Text.literal(
                        "Duel request sent to " +
                                target.getName().getString()
                ).formatted(Formatting.GREEN),
                false
        );

        target.sendMessage(
                Text.literal(
                        sender.getName().getString() +
                                " challenged you! Kit: " +
                                selectedKit +
                                ". Use /duel accept"
                ).formatted(Formatting.GOLD),
                false
        );

        return 1;
    }

    // ACCEPT DUEL

    private static int acceptDuel(ServerPlayerEntity target) {

        DuelRequest request =
                REQUESTS.remove(target.getUuid());

        if (request == null) {

            target.sendMessage(
                    Text.literal("No pending duel request."),
                    false
            );

            return 0;
        }

        MinecraftServer server = target.getServer();

        ServerPlayerEntity sender =
                server.getPlayerManager()
                        .getPlayer(request.sender);

        if (sender == null) {

            target.sendMessage(
                    Text.literal("Player is offline."),
                    false
            );

            return 0;
        }

        if (DUELS.containsKey(sender.getUuid()) ||
                DUELS.containsKey(target.getUuid())) {

            target.sendMessage(
                    Text.literal("A player is already dueling."),
                    false
            );

            return 0;
        }

        if (sender.getWorld() != target.getWorld()) {

            target.sendMessage(
                    Text.literal("Players must be in the same dimension."),
                    false
            );

            return 0;
        }

        List<ItemStack> kit = KITS.get(request.kit);

        if (kit == null) {
            return 0;
        }

        // Save original inventories

        List<ItemStack> senderInventory =
                copyInventory(sender);

        List<ItemStack> targetInventory =
                copyInventory(target);

        // Create duel session

        DuelSession duel = new DuelSession(
                sender.getUuid(),
                target.getUuid(),
                senderInventory,
                targetInventory
        );

        DUELS.put(sender.getUuid(), duel);
        DUELS.put(target.getUuid(), duel);

        // Give kits

        giveKit(sender, kit);
        giveKit(target, kit);

        // Teleport target beside sender
        // Same dimension only; no PositionFlag required.

        target.requestTeleport(
                sender.getX() + 3,
                sender.getY(),
                sender.getZ()
        );

        // Protect during countdown

        sender.setInvulnerable(true);
        target.setInvulnerable(true);

        sendToBoth(
                server,
                duel,
                "§e§lDUEL STARTING: 3"
        );

        playSoundToBoth(
                server,
                duel,
                SoundEvents.BLOCK_NOTE_BLOCK_PLING
        );

        return 1;
    }

    // COPY INVENTORY

    private static List<ItemStack> copyInventory(
            ServerPlayerEntity player
    ) {

        List<ItemStack> items = new ArrayList<>();

        for (int i = 0;
             i < player.getInventory().size();
             i++) {

            items.add(
                    player.getInventory()
                            .getStack(i)
                            .copy()
            );
        }

        return items;
    }

    // GIVE KIT

    private static void giveKit(
            ServerPlayerEntity player,
            List<ItemStack> kit
    ) {

        player.getInventory().clear();

        for (int i = 0;
             i < Math.min(
                     kit.size(),
                     player.getInventory().size()
             );
             i++) {

            player.getInventory().setStack(
                    i,
                    kit.get(i).copy()
            );
        }

        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
    }

    // RESTORE INVENTORY

    private static void restoreInventory(
            ServerPlayerEntity player,
            List<ItemStack> items
    ) {

        player.getInventory().clear();

        for (int i = 0;
             i < Math.min(
                     items.size(),
                     player.getInventory().size()
             );
             i++) {

            player.getInventory().setStack(
                    i,
                    items.get(i).copy()
            );
        }

        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
    }

    // DENY DUEL

    private static int denyDuel(ServerPlayerEntity target) {

        DuelRequest request =
                REQUESTS.remove(target.getUuid());

        if (request == null) {

            target.sendMessage(
                    Text.literal("No pending request."),
                    false
            );

            return 0;
        }

        ServerPlayerEntity sender =
                target.getServer()
                        .getPlayerManager()
                        .getPlayer(request.sender);

        target.sendMessage(
                Text.literal("Duel rejected."),
                false
        );

        if (sender != null) {

            sender.sendMessage(
                    Text.literal(
                            target.getName().getString() +
                                    " rejected your duel."
                    ),
                    false
            );
        }

        return 1;
    }

    // COUNTDOWN

    private static void tickDuels(MinecraftServer server) {

        Set<DuelSession> sessions =
                new HashSet<>(DUELS.values());

        for (DuelSession duel : sessions) {

            if (duel.started || duel.finished) {
                continue;
            }

            duel.ticks--;

            if (duel.ticks == 40) {

                sendToBoth(server, duel, "§e§l2");

                playSoundToBoth(
                        server,
                        duel,
                        SoundEvents.BLOCK_NOTE_BLOCK_PLING
                );
            }

            if (duel.ticks == 20) {

                sendToBoth(server, duel, "§e§l1");

                playSoundToBoth(
                        server,
                        duel,
                        SoundEvents.BLOCK_NOTE_BLOCK_PLING
                );
            }

            if (duel.ticks <= 0) {

                duel.started = true;

                ServerPlayerEntity a =
                        server.getPlayerManager()
                                .getPlayer(duel.first);

                ServerPlayerEntity b =
                        server.getPlayerManager()
                                .getPlayer(duel.second);

                if (a == null || b == null) {
                    finishDuel(server, duel, null);
                    continue;
                }

                a.setInvulnerable(false);
                b.setInvulnerable(false);

                sendToBoth(
                        server,
                        duel,
                        "§a§lFIGHT!"
                );

                playSoundToBoth(
                        server,
                        duel,
                        SoundEvents.ENTITY_PLAYER_LEVELUP
                );
            }
        }
    }

    // SEND MESSAGE

    private static void sendToBoth(
            MinecraftServer server,
            DuelSession duel,
            String message
    ) {

        ServerPlayerEntity a =
                server.getPlayerManager()
                        .getPlayer(duel.first);

        ServerPlayerEntity b =
                server.getPlayerManager()
                        .getPlayer(duel.second);

        if (a != null) {
            a.sendMessage(Text.literal(message), false);
        }

        if (b != null) {
            b.sendMessage(Text.literal(message), false);
        }
    }

    // PLAY SOUND

    private static void playSoundToBoth(
            MinecraftServer server,
            DuelSession duel,
            net.minecraft.sound.SoundEvent sound
    ) {

        ServerPlayerEntity a =
                server.getPlayerManager()
                        .getPlayer(duel.first);

        ServerPlayerEntity b =
                server.getPlayerManager()
                        .getPlayer(duel.second);

        if (a != null) {
            playSound(a, sound);
        }

        if (b != null) {
            playSound(b, sound);
        }
    }

    private static void playSound(
            ServerPlayerEntity player,
            net.minecraft.sound.SoundEvent sound
    ) {

        player.getServerWorld().playSound(
                null,
                player.getX(),
                player.getY(),
                player.getZ(),
                sound,
                SoundCategory.PLAYERS,
                1.0f,
                1.0f
        );
    }

    // PLAYER DEATH

    private static void onPlayerDeath(
            LivingEntity entity,
            DamageSource source
    ) {

        if (!(entity instanceof ServerPlayerEntity loser)) {
            return;
        }

        DuelSession duel =
                DUELS.get(loser.getUuid());

        if (duel == null || !duel.started) {
            return;
        }

        UUID winnerUuid =
                duel.first.equals(loser.getUuid())
                        ? duel.second
                        : duel.first;

        finishDuel(
                loser.getServer(),
                duel,
                winnerUuid
        );
    }

    // PLAYER DISCONNECT

    private static void handlePlayerDisconnect(
            ServerPlayerEntity quitter
    ) {

        UUID uuid = quitter.getUuid();

        REQUESTS.remove(uuid);

        REQUESTS.entrySet().removeIf(
                entry -> entry.getValue().sender.equals(uuid)
        );

        DuelSession duel = DUELS.get(uuid);

        if (duel == null) {
            return;
        }

        UUID winnerUuid =
                duel.first.equals(uuid)
                        ? duel.second
                        : duel.first;

        finishDuel(
                quitter.getServer(),
                duel,
                winnerUuid
        );
    }

    // FINISH DUEL

    private static void finishDuel(
            MinecraftServer server,
            DuelSession duel,
            UUID winnerUuid
    ) {

        if (duel.finished) {
            return;
        }

        duel.finished = true;

        DUELS.remove(duel.first);
        DUELS.remove(duel.second);

        ServerPlayerEntity first =
                server.getPlayerManager()
                        .getPlayer(duel.first);

        ServerPlayerEntity second =
                server.getPlayerManager()
                        .getPlayer(duel.second);

        // Restore original inventories for online players

        if (first != null) {

            first.setInvulnerable(false);

            restoreInventory(
                    first,
                    duel.firstInventory
            );
        }

        if (second != null) {

            second.setInvulnerable(false);

            restoreInventory(
                    second,
                    duel.secondInventory
            );
        }

        if (winnerUuid == null) {
            return;
        }

        ServerPlayerEntity winner =
                server.getPlayerManager()
                        .getPlayer(winnerUuid);

        if (winner != null) {
            announceWinner(server, winner);
        }
    }

    // ANNOUNCE WINNER

    private static void announceWinner(
            MinecraftServer server,
            ServerPlayerEntity winner
    ) {

        String name =
                winner.getName().getString();

        for (ServerPlayerEntity player :
                server.getPlayerManager().getPlayerList()) {

            player.networkHandler.sendPacket(
                    new TitleS2CPacket(
                            Text.literal(name)
                                    .formatted(Formatting.GOLD)
                    )
            );

            player.networkHandler.sendPacket(
                    new SubtitleS2CPacket(
                            Text.literal("KAZANDI!")
                                    .formatted(Formatting.GREEN)
                    )
            );
        }

        playSound(
                winner,
                SoundEvents.UI_TOAST_CHALLENGE_COMPLETE
        );
    }

    // DATA CLASSES

    private static class DuelRequest {

        final UUID sender;
        final String kit;

        DuelRequest(UUID sender, String kit) {
            this.sender = sender;
            this.kit = kit;
        }
    }

    private static class DuelSession {

        final UUID first;
        final UUID second;

        final List<ItemStack> firstInventory;
        final List<ItemStack> secondInventory;

        int ticks = 60;

        boolean started = false;
        boolean finished = false;

        DuelSession(
                UUID first,
                UUID second,
                List<ItemStack> firstInventory,
                List<ItemStack> secondInventory
        ) {

            this.first = first;
            this.second = second;

            this.firstInventory = firstInventory;
            this.secondInventory = secondInventory;
        }
    }
}
