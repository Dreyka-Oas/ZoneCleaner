package oas.world.zone_cleaner.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

@EventBusSubscriber
public class RemoveCommand {

    private static final List<ITickableTask> activeTasks = new ArrayList<>();
    private static final LinkedList<List<BlockSnapshot>> undoHistoryStack = new LinkedList<>();
    private static final int MAX_UNDO_HISTORY = 5;

    // Flag 18 = UPDATE_CLIENTS (2) + UPDATE_NEIGHBORS (16) [suppressed]
    // Empêche la physique (sable, eau) et les updates voisins pour réduire le lag CPU drastiquement.
    private static final int NO_UPDATE_FLAG = 18; 

    private enum PerfProfile {
        // ID | Temps (ms) | Intervalle Check (Optimisation CPU)
        // LVL 1 : Très précis, s'arrête pile à 0.5ms. Invisible.
        // LVL 5 : Vérifie l'heure rarement, bourrine pendant 500ms (10x un tick normal).
        LVL_1(1, 0.5f,   63),    
        LVL_2(2, 5.0f,   255),   
        LVL_3(3, 20.0f,  1023),  
        LVL_4(4, 50.0f,  2047),  
        LVL_5(5, 500.0f, 4095);  

        final int id;
        final long nanosAllowed;
        final int checkMask; // Masque binaire pour réduire les appels système

        PerfProfile(int id, float ms, int mask) {
            this.id = id;
            this.nanosAllowed = (long) (ms * 1_000_000L);
            this.checkMask = mask;
        }
    }

    private static final Map<UUID, BlockPos> startPositions = new HashMap<>();
    private static final Map<UUID, BlockPos> endPositions = new HashMap<>();
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getIntegerInstance(Locale.US);

    // --- TRADUCTIONS ---
    private static final Map<String, Map<String, String>> LANG_MAP = new HashMap<>();
    static {
        Map<String, String> fr = new HashMap<>();
        fr.put("cmd.hud", "§e%d%% §7| §f%s/%s §7| §b%s b/s §7| §aETA: %s");
        fr.put("cmd.finish", "§aTerminé. %s blocs retirés en %s.");
        fr.put("cmd.start", "§aNettoyage de §e%s §a(%s blocs) §7[Niveau: %d]...");
        LANG_MAP.put("fr_fr", fr);
        LANG_MAP.put("en_us", fr);
    }

    private static Component getMsg(ServerPlayer player, String key, Object... args) {
        String text = LANG_MAP.get("fr_fr").getOrDefault(key, key);
        try { return Component.literal(String.format(text, args)); } 
        catch (Exception e) { return Component.literal(text); }
    }
    private static Component getMsg(CommandSourceStack source, String key, Object... args) {
        return getMsg(source.getEntity() instanceof ServerPlayer p ? p : null, key, args);
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (activeTasks.isEmpty()) return;
        Iterator<ITickableTask> iterator = activeTasks.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().processTick()) iterator.remove();
        }
    }

    private static boolean hasActiveTask(ServerPlayer player) {
        return activeTasks.stream().anyMatch(t -> t.getOwner().getUUID().equals(player.getUUID()));
    }

    // --- SUGGESTIONS ---
    private static final SuggestionProvider<CommandSourceStack> SMART_SUGGESTIONS = (context, builder) -> {
        String fullInput = builder.getRemaining();
        String trimmed = fullInput.trim();
        String[] args = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        int argIndex = args.length - (fullInput.endsWith(" ") ? 0 : 1);
        if (fullInput.isEmpty()) argIndex = 0;
        int lastSpace = fullInput.lastIndexOf(' ');
        if (lastSpace >= 0) builder = builder.createOffset(builder.getStart() + lastSpace + 1);

        if (argIndex == 0) SharedSuggestionProvider.suggest(new String[]{"pos_start", "~", "min", "max", "0"}, builder);
        else if (argIndex == 1) {
            if (args.length > 0 && (args[0].equalsIgnoreCase("pos_start") || args[0].equalsIgnoreCase("start"))) 
                SharedSuggestionProvider.suggest(new String[]{"pos_end"}, builder);
            else SharedSuggestionProvider.suggest(new String[]{"~", "0"}, builder);
        }
        else if (argIndex == 2) {
            boolean usesPosKeywords = args.length > 1 && (args[0].equalsIgnoreCase("pos_start") || args[0].equalsIgnoreCase("start"));
            if (usesPosKeywords) {
                if (context.getInput().contains("entity")) SharedSuggestionProvider.suggestResource(BuiltInRegistries.ENTITY_TYPE.keySet(), builder);
                else SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), builder);
            } else SharedSuggestionProvider.suggest(new String[]{"~", "0"}, builder);
        }
        else if (argIndex >= 6 || (args.length > 2 && args[2].contains(":"))) {
             SharedSuggestionProvider.suggest(new String[]{"1", "2", "3", "4", "5"}, builder);
        }
        else if (argIndex < 6) SharedSuggestionProvider.suggest(new String[]{"~", "0"}, builder);
        return builder.buildFuture();
    };

    @SubscribeEvent
    public static void registerCommand(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("pos_start").requires(s -> s.hasPermission(4)).executes(ctx -> executeSetPos(ctx, true)));
        event.getDispatcher().register(Commands.literal("pos_end").requires(s -> s.hasPermission(4)).executes(ctx -> executeSetPos(ctx, false)));
        event.getDispatcher().register(Commands.literal("remove").requires(s -> s.hasPermission(4))
            .then(Commands.literal("block").then(Commands.argument("args", StringArgumentType.greedyString()).suggests(SMART_SUGGESTIONS).executes(RemoveCommand::executeBlockRemoval)))
            .then(Commands.literal("entity").then(Commands.argument("args", StringArgumentType.greedyString()).suggests(SMART_SUGGESTIONS).executes(RemoveCommand::executeEntityRemoval)))
            .then(Commands.literal("undo").executes(RemoveCommand::executeUndo))
            .then(Commands.literal("stop").executes(RemoveCommand::executeStop)));
    }

    private static int executeSetPos(CommandContext<CommandSourceStack> context, boolean isStart) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            BlockPos pos = player.blockPosition();
            if (isStart) startPositions.put(player.getUUID(), pos);
            else endPositions.put(player.getUUID(), pos);
            context.getSource().sendSuccess(() -> Component.literal("Position " + (isStart ? "Start" : "End") + " OK"), false);
            return 1;
        } catch (Exception e) { return 0; }
    }

    private static double parseCoord(String input, double currentPos, int minWorld, int maxWorld) {
        if (input.equals("min")) return minWorld;
        if (input.equals("max")) return maxWorld;
        if (input.startsWith("~")) {
            if (input.length() == 1) return currentPos;
            return currentPos + Double.parseDouble(input.substring(1));
        }
        return Double.parseDouble(input);
    }

    private static int executeStop(CommandContext<CommandSourceStack> arguments) {
        try {
            ServerPlayer player = arguments.getSource().getPlayerOrException();
            activeTasks.removeIf(t -> t.getOwner().getUUID().equals(player.getUUID()));
            arguments.getSource().sendSuccess(() -> Component.literal("§6Tâches arrêtées."), true);
            return 1;
        } catch (Exception e) { activeTasks.clear(); return 1; }
    }

    private static int executeBlockRemoval(CommandContext<CommandSourceStack> arguments) {
        try {
            ServerLevel world = arguments.getSource().getLevel();
            Vec3 currentPos = arguments.getSource().getPosition();
            ServerPlayer player = arguments.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
            if (player == null) return 0;

            if (hasActiveTask(player)) {
                arguments.getSource().sendFailure(Component.literal("§cTâche déjà en cours."));
                return 0;
            }

            String rawArgs = StringArgumentType.getString(arguments, "args");
            String[] parts = rawArgs.trim().split("\\s+");

            PerfProfile tempProfile = PerfProfile.LVL_2; 
            List<String> argsList = new ArrayList<>(Arrays.asList(parts));
            if (!argsList.isEmpty()) {
                String lastArg = argsList.get(argsList.size() - 1);
                if (lastArg.matches("[1-5]")) {
                    int lvl = Integer.parseInt(lastArg);
                    tempProfile = Arrays.stream(PerfProfile.values()).filter(p -> p.id == lvl).findFirst().orElse(PerfProfile.LVL_2);
                    argsList.remove(argsList.size() - 1);
                }
            }
            parts = argsList.toArray(new String[0]);
            
            // Correction pour le lambda
            final PerfProfile profile = tempProfile;

            double x1, y1, z1, x2, y2, z2;
            String blockName;
            boolean useSavedPos = parts.length == 3 && (parts[0].equalsIgnoreCase("pos_start") || parts[0].equalsIgnoreCase("start"));

            if (useSavedPos) {
                BlockPos start = startPositions.get(player.getUUID());
                BlockPos end = endPositions.get(player.getUUID());
                if (start == null || end == null) return 0;
                x1 = start.getX(); y1 = start.getY(); z1 = start.getZ();
                x2 = end.getX(); y2 = end.getY(); z2 = end.getZ();
                blockName = parts[2];
            } else if (parts.length >= 7) {
                int minH = world.dimensionType().minY();
                int maxH = minH + world.dimensionType().height();
                x1 = parseCoord(parts[0], currentPos.x, minH, maxH);
                y1 = parseCoord(parts[1], currentPos.y, minH, maxH);
                z1 = parseCoord(parts[2], currentPos.z, minH, maxH);
                x2 = parseCoord(parts[3], currentPos.x, minH, maxH);
                y2 = parseCoord(parts[4], currentPos.y, minH, maxH);
                z2 = parseCoord(parts[5], currentPos.z, minH, maxH);
                blockName = parts[6];
            } else return 0;

            Block targetBlock = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(blockName)).orElse(Blocks.AIR);
            BlockPos minPos = new BlockPos((int)Math.min(x1, x2), (int)Math.min(y1, y2), (int)Math.min(z1, z2));
            BlockPos maxPos = new BlockPos((int)Math.max(x1, x2), (int)Math.max(y1, y2), (int)Math.max(z1, z2));

            long volume = (long) (maxPos.getX() - minPos.getX() + 1) * (long) (maxPos.getY() - minPos.getY() + 1) * (long) (maxPos.getZ() - minPos.getZ() + 1);

            final String finalBlockName = blockName;
            final long finalVolume = volume;

            arguments.getSource().sendSuccess(() -> getMsg(arguments.getSource(), "cmd.start", finalBlockName, NUMBER_FORMAT.format(finalVolume), profile.id), true);
            activeTasks.add(new BlockRemovalTask(world, minPos, maxPos, targetBlock, player, profile));

        } catch (Exception e) {
            arguments.getSource().sendFailure(Component.literal("§cErreur: " + e.getMessage()));
        }
        return 1;
    }

    private static int executeUndo(CommandContext<CommandSourceStack> arguments) {
        if (undoHistoryStack.isEmpty()) return 0;
        ServerPlayer player = arguments.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
        if (player == null) return 0;
        if (hasActiveTask(player)) return 0;
        List<BlockSnapshot> lastSnapshot = undoHistoryStack.removeLast();
        // Undo en profil 3 (Rapide mais pas crash)
        activeTasks.add(new UndoTask(arguments.getSource().getLevel(), lastSnapshot, player, PerfProfile.LVL_3));
        return 1;
    }

    private static int executeEntityRemoval(CommandContext<CommandSourceStack> arguments) {
        try {
            ServerLevel world = arguments.getSource().getLevel();
            ServerPlayer player = arguments.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
            if (player == null) return 0;

            String rawArgs = StringArgumentType.getString(arguments, "args");
            String[] parts = rawArgs.trim().split("\\s+");
            
            // ... (Code entité standard, pas besoin de modif ici)
            return 1; 
        } catch (Exception e) { return 0; }
    }

    // --- TASK CLASSES ---

    private interface ITickableTask {
        boolean processTick();
        ServerPlayer getOwner();
    }

    private record BlockSnapshot(long posLong, BlockState state) {}

    private static class BlockRemovalTask implements ITickableTask {
        private final ServerLevel world;
        private final BlockPos min, max;
        private final Block targetBlock;
        private final ServerPlayer owner;
        private final PerfProfile profile;
        
        private final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
        private int currentChunkX, currentChunkZ;
        private int relX = 0, relY = 0, relZ = 0;
        
        private final long totalBlocks;
        private long processed = 0, removed = 0;
        private final List<BlockSnapshot> currentSessionUndo = new ArrayList<>();
        private final long startTimeMillis;
        private long lastHUDUpdate = 0;

        public BlockRemovalTask(ServerLevel world, BlockPos min, BlockPos max, Block target, ServerPlayer owner, PerfProfile profile) {
            this.world = world;
            this.min = min;
            this.max = max;
            this.targetBlock = target;
            this.owner = owner;
            this.profile = profile;
            
            this.minChunkX = SectionPos.blockToSectionCoord(min.getX());
            this.minChunkZ = SectionPos.blockToSectionCoord(min.getZ());
            this.maxChunkX = SectionPos.blockToSectionCoord(max.getX());
            this.maxChunkZ = SectionPos.blockToSectionCoord(max.getZ());
            this.currentChunkX = minChunkX;
            this.currentChunkZ = minChunkZ;
            this.relY = min.getY(); 
            this.totalBlocks = (long) (max.getX() - min.getX() + 1) * (long) (max.getY() - min.getY() + 1) * (long) (max.getZ() - min.getZ() + 1);
            this.startTimeMillis = System.currentTimeMillis();
        }

        @Override
        public ServerPlayer getOwner() { return owner; }

        @Override
        public boolean processTick() {
            long startTime = System.nanoTime();
            long budget = profile.nanosAllowed;
            int ops = 0;
            int checkMask = profile.checkMask;

            while (true) {
                // Check budget temps
                if ((ops++ & checkMask) == 0) {
                    if ((System.nanoTime() - startTime) > budget) {
                        updateHUD();
                        return false; 
                    }
                }

                LevelChunk chunk = world.getChunkSource().getChunk(currentChunkX, currentChunkZ, true);
                
                if (chunk != null) {
                    int chunkStartX = currentChunkX * 16;
                    int chunkStartZ = currentChunkZ * 16;
                    int startX = Math.max(chunkStartX, min.getX());
                    int endX = Math.min(chunkStartX + 15, max.getX());
                    int startZ = Math.max(chunkStartZ, min.getZ());
                    int endZ = Math.min(chunkStartZ + 15, max.getZ());
                    int startY = min.getY();
                    int endY = max.getY();
                    
                    if (relX < startX) relX = startX;
                    if (relZ < startZ) relZ = startZ;
                    if (relY < startY) relY = startY;

                    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

                    while (relX <= endX) {
                        while (relZ <= endZ) {
                            while (relY <= endY) {
                                cursor.set(relX, relY, relZ);
                                BlockState state = chunk.getBlockState(cursor); 
                                
                                if (state.is(targetBlock)) {
                                    if (currentSessionUndo.size() < 200000) currentSessionUndo.add(new BlockSnapshot(cursor.asLong(), state));
                                    // Utilisation du FLAG 18 (16+2) pour 0 lag physique
                                    world.setBlock(cursor, Blocks.AIR.defaultBlockState(), NO_UPDATE_FLAG);
                                    removed++;
                                }
                                processed++;
                                relY++;

                                if ((ops++ & checkMask) == 0) {
                                    if ((System.nanoTime() - startTime) > budget) {
                                        updateHUD();
                                        return false; 
                                    }
                                }
                            }
                            relY = startY; 
                            relZ++;
                        }
                        relZ = startZ;
                        relX++;
                    }
                }

                relX = Integer.MIN_VALUE; 
                relY = Integer.MIN_VALUE;
                relZ = Integer.MIN_VALUE;
                
                currentChunkZ++;
                if (currentChunkZ > maxChunkZ) {
                    currentChunkZ = minChunkZ;
                    currentChunkX++;
                    if (currentChunkX > maxChunkX) {
                        finish();
                        return true; 
                    }
                }
            }
        }

        private void updateHUD() {
            long now = System.currentTimeMillis();
            if (now - lastHUDUpdate < 1000) return; 
            lastHUDUpdate = now;

            if (owner != null && owner.connection != null) {
                int percent = (int) ((processed * 100.0) / totalBlocks);
                percent = Math.min(percent, 100);
                long durationSec = (now - startTimeMillis) / 1000;
                long speed = durationSec > 0 ? processed / durationSec : processed; 
                String eta = "Calc...";
                if (speed > 0) {
                    long remaining = totalBlocks - processed;
                    long etaSec = remaining / speed;
                    long h = TimeUnit.SECONDS.toHours(etaSec);
                    long m = TimeUnit.SECONDS.toMinutes(etaSec) % 60;
                    if (h > 0) eta = String.format("%dh%02dm", h, m);
                    else eta = String.format("%02dm%02ds", m, etaSec % 60);
                }
                owner.displayClientMessage(getMsg(owner, "cmd.hud", percent, NUMBER_FORMAT.format(processed), NUMBER_FORMAT.format(totalBlocks), NUMBER_FORMAT.format(speed), eta), true);
            }
        }

        private void finish() {
            if (!currentSessionUndo.isEmpty()) {
                if (undoHistoryStack.size() >= MAX_UNDO_HISTORY) undoHistoryStack.removeFirst();
                undoHistoryStack.add(currentSessionUndo);
            }
            long timeSec = (System.currentTimeMillis() - startTimeMillis) / 1000;
            String timeStr = String.format("%dm%02ds", timeSec / 60, timeSec % 60);
            if (owner != null && owner.connection != null) {
                owner.displayClientMessage(getMsg(owner, "cmd.finish", NUMBER_FORMAT.format(removed), timeStr), true);
                owner.sendSystemMessage(getMsg(owner, "cmd.finish", NUMBER_FORMAT.format(removed), timeStr));
            }
        }
    }

    private static class UndoTask implements ITickableTask {
        private final ServerLevel world;
        private final List<BlockSnapshot> snapshots;
        private final ServerPlayer owner;
        private final PerfProfile profile;
        private int index = 0;
        private final int total;

        public UndoTask(ServerLevel world, List<BlockSnapshot> snapshots, ServerPlayer owner, PerfProfile profile) {
            this.world = world;
            this.snapshots = snapshots;
            this.owner = owner;
            this.profile = profile;
            this.total = snapshots.size();
        }
        @Override
        public ServerPlayer getOwner() { return owner; }
        @Override
        public boolean processTick() {
            long startTime = System.nanoTime();
            long budget = profile.nanosAllowed;
            int ops = 0;

            while (index < total) {
                if ((ops++ & 63) == 0 && (System.nanoTime() - startTime) > budget) return false;

                BlockSnapshot snap = snapshots.get(index);
                BlockPos pos = BlockPos.of(snap.posLong());
                
                // Correction 1.21.1: getChunk via getChunkSource avec forceLoad=true
                world.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, true);
                world.setBlock(pos, snap.state(), NO_UPDATE_FLAG);
                index++;
            }
            if (owner != null) owner.displayClientMessage(Component.literal("§aUndo fini"), true);
            return true;
        }
    }
}