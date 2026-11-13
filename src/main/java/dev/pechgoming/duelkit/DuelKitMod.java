package dev.pechhoming.duelkit;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.PositionFlag;

import com.mojang.brigadier.arguments.StringArgumentType;

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

        System.out.println("[DuelKit] Mod loaded!");
    }

    private void registerCommands() {

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, environment) -> {

            // ADMIN COMMANDS

            dispatcher.register(literal("pvp")
                .requires(source ->
                    source.hasPermissionLevel(2)
                )
                .then(literal("kit")
                    .executes(ctx -> {
                        ServerPlayerEntity p =
                            ctx.getSource().getPlayerOrThrow();

                        p.sendMessage(Text.literal(
                            "§6§lDUEL KIT MENU"
                        ), false);

                        p.sendMessage(Text.literal(
                            "§e/pvp kit create <name>"
                        ), false);

                        p.sendMessage(Text.literal(
                            "§e/pvp kit list"
                        ), false);

                        p.sendMessage(Text.literal(
                            "§e/pvp kit delete <name>"
                        ), false);

                        return 1;
                    })

                    .then(literal("create")
                        .then(argument("name",
                            StringArgumentType.word())
                            .executes(ctx -> saveKit(
                                ctx.getSource().getPlayerOrThrow(),
                                StringArgumentType.getString(
                                    ctx, "name"
                                )
                            ))
                        )
                    )

                    .then(literal("list")
                        .executes(ctx -> listKits(
                            ctx.getSource().getPlayerOrThrow()
                        ))
                    )

                    .then(literal("delete")
                        .then(argument("name",
                            StringArgumentType.word())
                            .executes(ctx -> deleteKit(
                                ctx.getSource().getPlayerOrThrow(),
                                StringArgumentType.getString(
                                    ctx, "name"
                                )
                            ))
                        )
                    )
                )
            );

            // PLAYER COMMANDS

            dispatcher.register(literal("duel")

                .then(literal("accept")
                    .executes(ctx -> acceptDuel(
                        ctx.getSource().getPlayerOrThrow()
                    ))
                )

                .then(literal("deny")
                    .executes(ctx -> denyDuel(
                        ctx.getSource().getPlayerOrThrow()
                    ))
                )

                .then(argument("player",
                    StringArgumentType.word())

                    .executes(ctx -> requestDuel(
                        ctx.getSource().getPlayerOrThrow(),
                        StringArgumentType.getString(ctx, "player"),
                        null
                    ))

                    .then(argument("kit",
                        StringArgumentType.word())

                        .executes(ctx -> requestDuel(
                            ctx.getSource().getPlayerOrThrow(),
                            StringArgumentType.getString(ctx, "player"),
                            StringArgumentType.getString(ctx, "kit")
                        ))
                    )
                )
            );
        });
    }

    // SAVE KIT

    private static int saveKit(
            ServerPlayerEntity admin,
            String name
    ) {

        List<ItemStack> items = new ArrayList<>();

        for (int i = 0;
             i < admin.getInventory().size();
             i++) {

            items.add(
                admin.getInventory()
                    .getStack(i)
                    .copy()
            );
        }

        KITS.put(
            name.toLowerCase(Locale.ROOT),
            items
        );

        admin.sendMessage(
            Text.literal("Kit saved: " + name)
                .formatted(Formatting.GREEN),
            false
        );

        return 1;
    }

    // LIST KITS

    private static int listKits(
            ServerPlayerEntity player
    ) {

        if (KITS.isEmpty()) {
            player.sendMessage(
                Text.literal("No kits available."),
                false
            );
            return 1;
        }

        player.sendMessage(
            Text.literal("Available kits: " +
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
                Text.literal("Kit deleted: " + name)
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

        if (DUELS.containsKey(sender.getUuid()) ||
            DUELS.containsKey(target.getUuid())) {

            sender.sendMessage(
                Text.literal("One of the players is already dueling."),
                false
            );
            return 0;
        }

        if (KITS.isEmpty()) {
            sender.sendMessage(
                Text.literal("No duel kits available."),
                false
            );
            return 0;
        }

        String selectedKit = kitName;

        if (selectedKit == null) {
            selectedKit = KITS.keySet().iterator().next();
        }

        selectedKit = selectedKit.toLowerCase(Locale.ROOT);

        if (!KITS.containsKey(selectedKit)) {
            sender.sendMessage(
                Text.literal("Kit not found."),
                false
            );
            return 0;
        }

        REQUESTS.put(
            target.getUuid(),
            new DuelRequest(sender.getUuid(), selectedKit)
        );

        sender.sendMessage(
            Text.literal("Duel request sent to " +
                target.getName().getString())
                .formatted(Formatting.GREEN),
            false
        );

        target.sendMessage(
            Text.literal(sender.getName().getString() +
                " challenged you! Use /duel accept or /duel deny")
                .formatted(Formatting.GOLD),
            false
        );

        return 1;
    }

    // ACCEPT DUEL

    private static int acceptDuel(
            ServerPlayerEntity target
    ) {

        DuelRequest request =
            REQUESTS.remove(target.getUuid());

        if (request == null) {
            target.sendMessage(
                Text.literal("No pending duel request."),
                false
            );
            return 0;
        }

        ServerPlayerEntity sender =
            target.getServer()
                .getPlayerManager()
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
                Text.literal("A player is already in a duel."),
                false
            );
            return 0;
        }

        List<ItemStack> kit = KITS.get(request.kit);

        if (kit == null) {
            target.sendMessage(
                Text.literal("Selected kit no longer exists."),
                false
            );
            return 0;
        }

        // Give kit

        giveKit(sender, kit);
        giveKit(target, kit);

        // Teleport target beside sender

        ServerWorld world = sender.getServerWorld();

        target.teleport(
            world,
            sender.getX() + 3,
            sender.getY(),
            sender.getZ(),
            EnumSet.noneOf(PositionFlag.class),
            sender.getYaw(),
            sender.getPitch()
        );

        DuelSession session =
            new DuelSession(sender, target);

        DUELS.put(sender.getUuid(), session);
        DUELS.put(target.getUuid(), session);

        sender.sendMessage(
            Text.literal("Duel starting in 3 seconds!")
                .formatted(Formatting.YELLOW),
            false
        );

        target.sendMessage(
            Text.literal("Duel starting in 3 seconds!")
                .formatted(Formatting.YELLOW),
            false
        );

        sender.playSound(
            SoundEvents.BLOCK_NOTE_BLOCK_PLING,
            1.0f, 1.0f
        );

        target.playSound(
            SoundEvents.BLOCK_NOTE_BLOCK_PLING,
            1.0f, 1.0f
        );

        return 1;
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
    }

    // DENY DUEL

    private static int denyDuel(
            ServerPlayerEntity target
    ) {

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

    private static void tickDuels(
            MinecraftServer server
    ) {

        Set<DuelSession> sessions =
            new HashSet<>(DUELS.values());

        for (DuelSession duel : sessions) {

            if (duel.started) {
                continue;
            }

            duel.ticks--;

            if (duel.ticks > 0 &&
                duel.ticks % 20 == 0) {

                int seconds = duel.ticks / 20;

                sendToBoth(
                    duel,
                    "§e" + seconds + "..."
                );

                playCountdownSound(duel);
            }

            if (duel.ticks <= 0) {

                duel.started = true;

                sendToBoth(
                    duel,
                    "§a§lFIGHT!"
                );

                playFightSound(duel);
            }
        }
    }

    private static void sendToBoth(
            DuelSession duel,
            String message
    ) {

        ServerPlayerEntity a =
            duel.first.getServer()
                .getPlayerManager()
                .getPlayer(duel.first.getUuid());

        ServerPlayerEntity b =
            duel.second.getServer()
                .getPlayerManager()
                .getPlayer(duel.second.getUuid());

        if (a != null) {
            a.sendMessage(
                Text.literal(message),
                false
            );
        }

        if (b != null) {
            b.sendMessage(
                Text.literal(message),
                false
            );
        }
    }

    private static void playCountdownSound(
            DuelSession duel
    ) {

        duel.first.playSound(
            SoundEvents.BLOCK_NOTE_BLOCK_PLING,
            1.0f, 1.0f
        );

        duel.second.playSound(
            SoundEvents.BLOCK_NOTE_BLOCK_PLING,
            1.0f, 1.0f
        );
    }

    private static void playFightSound(
            DuelSession duel
    ) {

        duel.first.playSound(
            SoundEvents.ENTITY_PLAYER_LEVELUP,
            1.0f, 1.0f
        );

        duel.second.playSound(
            SoundEvents.ENTITY_PLAYER_LEVELUP,
            1.0f, 1.0f
        );
    }

    // WIN DETECTION

    private static void onPlayerDeath(
            LivingEntity entity,
            DamageSource source
    ) {

        if (!(entity instanceof ServerPlayerEntity loser)) {
            return;
        }

        DuelSession duel =
            DUELS.remove(loser.getUuid());

        if (duel == null || !duel.started) {
            return;
        }

        ServerPlayerEntity winner =
            duel.first.getUuid().equals(loser.getUuid())
                ? duel.second
                : duel.first;

        DUELS.remove(winner.getUuid());

        String winnerName =
            winner.getName().getString();

        // Victory title

        for (ServerPlayerEntity player :
                winner.getServer()
                    .getPlayerManager()
                    .getPlayerList()) {

            player.networkHandler.sendPacket(
                new TitleS2CPacket(
                    Text.literal(winnerName)
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

        winner.playSound(
            SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
            1.0f, 1.0f
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

        final ServerPlayerEntity first;
        final ServerPlayerEntity second;

        int ticks = 60;
        boolean started = false;

        DuelSession(
                ServerPlayerEntity first,
                ServerPlayerEntity second
        ) {
            this.first = first;
            this.second = second;
        }
    }
                    }
           
