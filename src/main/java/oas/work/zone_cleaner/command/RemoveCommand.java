package oas.world.zone_cleaner.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
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

import java.util.*;

@EventBusSubscriber
public class RemoveCommand {

    private static final List<ITickableTask> activeTasks = new ArrayList<>();
    private static final LinkedList<List<BlockSnapshot>> undoHistoryStack = new LinkedList<>();
    private static final int MAX_UNDO_HISTORY = 5;
    
    private static final long MIN_TIME_SLICE = 500_000L;

    private static final Map<UUID, BlockPos> startPositions = new HashMap<>();
    private static final Map<UUID, BlockPos> endPositions = new HashMap<>();

    private static long tickStartNanos = 0L;
    private static float currentMSPT = 0.0f;

    @SubscribeEvent
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        tickStartNanos = System.nanoTime();
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        long tickEndNanos = System.nanoTime();
        if (tickStartNanos != 0) {
            long duration = tickEndNanos - tickStartNanos;
            float instantMSPT = duration / 1_000_000f;
            currentMSPT = (currentMSPT * 0.8f) + (instantMSPT * 0.2f);
        }

        if (activeTasks.isEmpty()) return;
        Iterator<ITickableTask> iterator = activeTasks.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().processTick()) iterator.remove();
        }
    }

    private static float getInternalMSPT() {
        if (currentMSPT <= 0) return 20.0f; 
        return currentMSPT;
    }

    private static boolean hasActiveTask(ServerPlayer player) {
        for (ITickableTask task : activeTasks) {
            if (task.getOwner().getUUID().equals(player.getUUID())) {
                return true;
            }
        }
        return false;
    }

    private static Component getMsg(String key, Object... args) {
        String translation = Language.getInstance().getOrDefault(key);
        try {
            return Component.literal(String.format(translation, args));
        } catch (Exception e) {
            return Component.literal(translation);
        }
    }

    private static final SuggestionProvider<CommandSourceStack> SMART_SUGGESTIONS = (context, builder) -> {
        String fullInput = builder.getRemaining();
        String trimmed = fullInput.trim();
        String[] args = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        
        int argIndex = args.length - (fullInput.endsWith(" ") ? 0 : 1);
        if (fullInput.isEmpty()) argIndex = 0;

        int lastSpace = fullInput.lastIndexOf(' ');
        if (lastSpace >= 0) {
            builder = builder.createOffset(builder.getStart() + lastSpace + 1);
        }

        if (argIndex == 0) {
            SharedSuggestionProvider.suggest(new String[]{"pos_start", "~", "min", "max", "0"}, builder);
        }
        else if (argIndex == 1) {
            if (args.length > 0 && (args[0].equalsIgnoreCase("pos_start") || args[0].equalsIgnoreCase("start"))) {
                SharedSuggestionProvider.suggest(new String[]{"pos_end"}, builder);
            } else {
                SharedSuggestionProvider.suggest(new String[]{"~", "0"}, builder);
            }
        }
        else if (argIndex == 2) {
            boolean usesPosKeywords = args.length > 1 && 
                (args[0].equalsIgnoreCase("pos_start") || args[0].equalsIgnoreCase("start")) &&
                (args[1].equalsIgnoreCase("pos_end") || args[1].equalsIgnoreCase("end"));

            if (usesPosKeywords) {
                if (context.getInput().contains("entity")) {
                    SharedSuggestionProvider.suggestResource(BuiltInRegistries.ENTITY_TYPE.keySet(), builder);
                } else {
                    SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), builder);
                }
            } else {
                SharedSuggestionProvider.suggest(new String[]{"~", "0"}, builder);
            }
        }
        else if (argIndex == 6) {
             if (context.getInput().contains("entity")) {
                SharedSuggestionProvider.suggestResource(BuiltInRegistries.ENTITY_TYPE.keySet(), builder);
            } else {
                SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), builder);
            }
        }
        else if (argIndex < 6) {
             SharedSuggestionProvider.suggest(new String[]{"~", "0"}, builder);
        }

        return builder.buildFuture();
    };

    @SubscribeEvent
    public static void registerCommand(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("pos_start")
            .requires(s -> s.hasPermission(4))
            .executes(ctx -> executeSetPos(ctx, true)));

        event.getDispatcher().register(Commands.literal("pos_end")
            .requires(s -> s.hasPermission(4))
            .executes(ctx -> executeSetPos(ctx, false)));

        event.getDispatcher().register(Commands.literal("remove")
            .requires(s -> s.hasPermission(4))
            .then(Commands.literal("block")
                .then(Commands.argument("args", StringArgumentType.greedyString())
                    .suggests(SMART_SUGGESTIONS)
                    .executes(RemoveCommand::executeBlockRemoval)))
            .then(Commands.literal("entity")
                .then(Commands.argument("args", StringArgumentType.greedyString())
                    .suggests(SMART_SUGGESTIONS)
                    .executes(RemoveCommand::executeEntityRemoval)))
            .then(Commands.literal("undo")
                .executes(RemoveCommand::executeUndo))
            .then(Commands.literal("stop")
                .executes(RemoveCommand::executeStop)));
    }

    private static int executeSetPos(CommandContext<CommandSourceStack> context, boolean isStart) {
        try {
            ServerPlayer player = context.getSource().getPlayerOrException();
            BlockPos pos = player.blockPosition();
            UUID uuid = player.getUUID();

            if (isStart) {
                startPositions.put(uuid, pos);
                context.getSource().sendSuccess(() -> getMsg("commands.zone_cleaner.pos.set.start", pos.toShortString()), false);
            } else {
                endPositions.put(uuid, pos);
                context.getSource().sendSuccess(() -> getMsg("commands.zone_cleaner.pos.set.end", pos.toShortString()), false);
            }
            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("Vous devez être un joueur pour utiliser cette commande."));
            return 0;
        }
    }

    private static double parseCoord(String input, double currentPos, int minWorld, int maxWorld) throws NumberFormatException {
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
            int removedCount = 0;
            
            Iterator<ITickableTask> it = activeTasks.iterator();
            while (it.hasNext()) {
                ITickableTask task = it.next();
                if (task.getOwner().getUUID().equals(player.getUUID())) {
                    it.remove();
                    removedCount++;
                }
            }
            
            final int count = removedCount;
            arguments.getSource().sendSuccess(() -> getMsg("commands.zone_cleaner.stop", count), true);
            return 1;
        } catch (Exception e) {
             int count = activeTasks.size();
             activeTasks.clear();
             arguments.getSource().sendSuccess(() -> getMsg("commands.zone_cleaner.stop", count), true);
             return 1;
        }
    }

    private static int executeBlockRemoval(CommandContext<CommandSourceStack> arguments) {
        try {
            ServerLevel world = arguments.getSource().getLevel();
            Vec3 currentPos = arguments.getSource().getPosition();
            ServerPlayer player = arguments.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
            
            if (player == null) return 0;

            if (hasActiveTask(player)) {
                arguments.getSource().sendFailure(getMsg("commands.zone_cleaner.error.already_running"));
                return 0;
            }

            String rawArgs = StringArgumentType.getString(arguments, "args");
            String[] parts = rawArgs.trim().split("\\s+");

            double x1, y1, z1, x2, y2, z2;
            String blockName;

            boolean useSavedPos = parts.length == 3 && 
                                  (parts[0].equalsIgnoreCase("pos_start") || parts[0].equalsIgnoreCase("start")) &&
                                  (parts[1].equalsIgnoreCase("pos_end") || parts[1].equalsIgnoreCase("end"));

            if (useSavedPos) {
                BlockPos start = startPositions.get(player.getUUID());
                BlockPos end = endPositions.get(player.getUUID());

                if (start == null || end == null) {
                    arguments.getSource().sendFailure(getMsg("commands.zone_cleaner.error.pos_not_set"));
                    return 0;
                }
                x1 = start.getX(); y1 = start.getY(); z1 = start.getZ();
                x2 = end.getX(); y2 = end.getY(); z2 = end.getZ();
                blockName = parts[2];
            } 
            else if (parts.length >= 7) {
                int minH = world.dimensionType().minY();
                int maxH = minH + world.dimensionType().height();

                x1 = parseCoord(parts[0], currentPos.x, minH, maxH);
                y1 = parseCoord(parts[1], currentPos.y, minH, maxH);
                z1 = parseCoord(parts[2], currentPos.z, minH, maxH);
                
                x2 = parseCoord(parts[3], currentPos.x, minH, maxH);
                y2 = parseCoord(parts[4], currentPos.y, minH, maxH);
                z2 = parseCoord(parts[5], currentPos.z, minH, maxH);
                blockName = parts[6];
            } else {
                arguments.getSource().sendFailure(getMsg("commands.zone_cleaner.error.generic", "Format invalide."));
                return 0;
            }

            Block targetBlock = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(blockName)).orElse(Blocks.AIR);

            if (targetBlock == Blocks.AIR && !blockName.equals("minecraft:air") && !blockName.equals("air")) {
                arguments.getSource().sendFailure(getMsg("commands.zone_cleaner.error.block_not_found", blockName));
                return 0;
            }

            BlockPos minPos = new BlockPos((int)Math.min(x1, x2), (int)Math.min(y1, y2), (int)Math.min(z1, z2));
            BlockPos maxPos = new BlockPos((int)Math.max(x1, x2), (int)Math.max(y1, y2), (int)Math.max(z1, z2));

            long volume = (long) (maxPos.getX() - minPos.getX() + 1) * 
                          (long) (maxPos.getY() - minPos.getY() + 1) * 
                          (long) (maxPos.getZ() - minPos.getZ() + 1);

            arguments.getSource().sendSuccess(() -> getMsg("commands.zone_cleaner.start.block", blockName, volume), true);
            
            activeTasks.add(new BlockRemovalTask(world, minPos, maxPos, targetBlock, player));

        } catch (Exception e) {
            arguments.getSource().sendFailure(getMsg("commands.zone_cleaner.error.generic", e.getMessage()));
            e.printStackTrace();
        }
        return 1;
    }

    private static int executeUndo(CommandContext<CommandSourceStack> arguments) {
        if (undoHistoryStack.isEmpty()) {
            arguments.getSource().sendFailure(getMsg("commands.zone_cleaner.undo.empty"));
            return 0;
        }

        ServerPlayer player = arguments.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
        if (player == null) return 0;

        if (hasActiveTask(player)) {
            arguments.getSource().sendFailure(getMsg("commands.zone_cleaner.error.already_running"));
            return 0;
        }

        ServerLevel world = arguments.getSource().getLevel();
        List<BlockSnapshot> lastSnapshot = undoHistoryStack.removeLast();
        arguments.getSource().sendSuccess(() -> getMsg("commands.zone_cleaner.undo.start", lastSnapshot.size()), true);
        activeTasks.add(new UndoTask(world, lastSnapshot, player));
        return 1;
    }

    private static int executeEntityRemoval(CommandContext<CommandSourceStack> arguments) {
        try {
            ServerLevel world = arguments.getSource().getLevel();
            Vec3 currentPos = arguments.getSource().getPosition();
            ServerPlayer player = arguments.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
            
            if (player == null) return 0;

            String rawArgs = StringArgumentType.getString(arguments, "args");
            String[] parts = rawArgs.trim().split("\\s+");

            double x1, y1, z1, x2, y2, z2;
            String entityName;

            boolean useSavedPos = parts.length == 3 && 
                                  (parts[0].equalsIgnoreCase("pos_start") || parts[0].equalsIgnoreCase("start")) &&
                                  (parts[1].equalsIgnoreCase("pos_end") || parts[1].equalsIgnoreCase("end"));

            if (useSavedPos) {
                BlockPos start = startPositions.get(player.getUUID());
                BlockPos end = endPositions.get(player.getUUID());

                if (start == null || end == null) {
                    arguments.getSource().sendFailure(getMsg("commands.zone_cleaner.error.pos_not_set"));
                    return 0;
                }
                x1 = start.getX(); y1 = start.getY(); z1 = start.getZ();
                x2 = end.getX(); y2 = end.getY(); z2 = end.getZ();
                entityName = parts[2];
            }
            else if (parts.length >= 7) {
                int minH = world.dimensionType().minY();
                int maxH = minH + world.dimensionType().height();

                x1 = parseCoord(parts[0], currentPos.x, minH, maxH);
                y1 = parseCoord(parts[1], currentPos.y, minH, maxH);
                z1 = parseCoord(parts[2], currentPos.z, minH, maxH);
                
                x2 = parseCoord(parts[3], currentPos.x, minH, maxH);
                y2 = parseCoord(parts[4], currentPos.y, minH, maxH);
                z2 = parseCoord(parts[5], currentPos.z, minH, maxH);
                entityName = parts[6];
            } else {
                arguments.getSource().sendFailure(getMsg("commands.zone_cleaner.error.generic", "Format invalide."));
                return 0;
            }
            
            EntityType<?> targetType = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(entityName)).orElse(null);

            if (targetType == null && !entityName.equals("all")) {
                arguments.getSource().sendFailure(getMsg("commands.zone_cleaner.error.entity_not_found", entityName));
                return 0;
            }

            AABB area = new AABB(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2) + 1, Math.max(y1, y2) + 1, Math.max(z1, z2) + 1);
            List<Entity> entities = world.getEntities(null, area);
            int count = 0;

            for (Entity entity : entities) {
                if (entity instanceof ServerPlayer) continue;
                if (entityName.equals("all") || entity.getType().equals(targetType)) {
                    entity.discard();
                    count++;
                }
            }

            final int finalCount = count;
            arguments.getSource().sendSuccess(() -> getMsg("commands.zone_cleaner.finish.entity", finalCount), true);

        } catch (Exception e) {
            arguments.getSource().sendFailure(getMsg("commands.zone_cleaner.error.generic", e.getMessage()));
            e.printStackTrace();
        }
        return 1;
    }

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
        private int cx, cy, cz;
        private final long totalBlocks;
        private long processed = 0, removed = 0;
        private final List<BlockSnapshot> currentSessionUndo = new ArrayList<>();

        public BlockRemovalTask(ServerLevel world, BlockPos min, BlockPos max, Block target, ServerPlayer owner) {
            this.world = world;
            this.min = min;
            this.max = max;
            this.targetBlock = target;
            this.owner = owner;
            this.cx = min.getX(); this.cy = min.getY(); this.cz = min.getZ();
            this.totalBlocks = (long) (max.getX() - min.getX() + 1) * (long) (max.getY() - min.getY() + 1) * (long) (max.getZ() - min.getZ() + 1);
        }

        @Override
        public ServerPlayer getOwner() { return owner; }

        @Override
        public boolean processTick() {
            float averageMSPT = getInternalMSPT();
            if (averageMSPT > 50.0f) return false; 

            long allowedNanos = (long) ((50.0f - averageMSPT) * 1_000_000L * 0.8f);
            allowedNanos = Math.min(allowedNanos, 40_000_000L);
            allowedNanos = Math.max(allowedNanos, MIN_TIME_SLICE);

            long startTime = System.nanoTime();
            int checkInterval = 0; 

            while (true) {
                if ((checkInterval++ & 15) == 0) {
                    if ((System.nanoTime() - startTime) > allowedNanos) break; 
                }

                BlockPos pos = new BlockPos(cx, cy, cz);
                LevelChunk chunk = world.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, true);
                
                if (chunk != null) {
                    BlockState state = chunk.getBlockState(pos);
                    if (state.is(targetBlock)) {
                        currentSessionUndo.add(new BlockSnapshot(pos.asLong(), state));
                        world.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        removed++;
                    }
                }

                processed++;
                cz++;
                if (cz > max.getZ()) {
                    cz = min.getZ(); cy++;
                    if (cy > max.getY()) {
                        cy = min.getY(); cx++;
                        if (cx > max.getX()) {
                            finish();
                            return true;
                        }
                    }
                }
            }
            updateHUD();
            return false;
        }

        private void updateHUD() {
            if (owner != null && owner.connection != null) {
                int p = (int) ((processed * 100.0) / totalBlocks);
                long remaining = totalBlocks - processed;
                owner.displayClientMessage(getMsg("commands.zone_cleaner.progress", p, processed, removed, remaining), true);
            }
        }

        private void finish() {
            if (!currentSessionUndo.isEmpty()) {
                if (undoHistoryStack.size() >= MAX_UNDO_HISTORY) undoHistoryStack.removeFirst();
                undoHistoryStack.add(currentSessionUndo);
            }
            if (owner != null && owner.connection != null) {
                owner.displayClientMessage(getMsg("commands.zone_cleaner.finish.block", removed), true);
                owner.sendSystemMessage(getMsg("commands.zone_cleaner.finish.chat", removed));
            }
        }
    }

    private static class UndoTask implements ITickableTask {
        private final ServerLevel world;
        private final List<BlockSnapshot> snapshots;
        private final ServerPlayer owner;
        private int index = 0;
        private final int total;

        public UndoTask(ServerLevel world, List<BlockSnapshot> snapshots, ServerPlayer owner) {
            this.world = world;
            this.snapshots = snapshots;
            this.owner = owner;
            this.total = snapshots.size();
        }

        @Override
        public ServerPlayer getOwner() { return owner; }

        @Override
        public boolean processTick() {
            float averageMSPT = getInternalMSPT();
            if (averageMSPT > 50.0f) return false;

            long allowedNanos = (long) ((50.0f - averageMSPT) * 1_000_000L * 0.8f);
            allowedNanos = Math.min(allowedNanos, 40_000_000L);
            allowedNanos = Math.max(allowedNanos, MIN_TIME_SLICE);

            long startTime = System.nanoTime();
            int checkInterval = 0;

            while (index < total) {
                if ((checkInterval++ & 15) == 0) {
                     if ((System.nanoTime() - startTime) > allowedNanos) break;
                }

                BlockSnapshot snap = snapshots.get(index);
                BlockPos pos = BlockPos.of(snap.posLong());
                world.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, true);
                world.setBlock(pos, snap.state(), 2);
                index++;
            }
            
            if (owner != null && owner.connection != null) {
                int p = (int) ((index * 100.0) / total);
                owner.displayClientMessage(getMsg("commands.zone_cleaner.undo.progress", p, index, total), true);
            }
            if (index >= total) {
                if (owner != null) owner.displayClientMessage(getMsg("commands.zone_cleaner.undo.finish"), true);
                return true;
            }
            return false;
        }
    }
}