package oas.world.zone_cleaner.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

@EventBusSubscriber
public class RemoveCommand {

    private static final List<ITickableTask> activeTasks = new ArrayList<>();
    private static final LinkedList<List<BlockSnapshot>> undoHistoryStack = new LinkedList<>();
    private static final int MAX_UNDO_HISTORY = 5;
    private static final int BLOCKS_PER_TICK = 256;
    private static final long MAX_NANO_TIME_PER_TICK = 40_000_000L;

    // Suggestion intelligente qui compte les espaces pour savoir quoi proposer
    private static final SuggestionProvider<CommandSourceStack> SMART_SUGGESTIONS = (context, builder) -> {
        String remaining = builder.getRemaining();
        // On compte les espaces pour savoir à quel argument on est (x1, y1, etc.)
        int spaces = remaining.length() - remaining.replace(" ", "").length();
        
        if (spaces < 6) {
            // C'est une coordonnée
            return SharedSuggestionProvider.suggest(new String[]{"~", "~10", "~-10", "min", "max", "0"}, builder);
        } else if (spaces == 6) {
            // C'est le bloc ou l'entité (7ème argument)
            if (context.getInput().contains("entity")) {
                return SharedSuggestionProvider.suggestResource(BuiltInRegistries.ENTITY_TYPE.keySet(), builder);
            } else {
                return SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), builder);
            }
        }
        return builder.buildFuture();
    };

    @SubscribeEvent
    public static void registerCommand(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("remove")
            .requires(s -> s.hasPermission(4))
            .then(Commands.literal("block")
                // On prend TOUT le reste de la commande comme une seule phrase
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
        int count = activeTasks.size();
        activeTasks.clear();
        arguments.getSource().sendSuccess(() -> Component.translatable("commands.zone_cleaner.stop", count), true);
        return 1;
    }

    private static int executeBlockRemoval(CommandContext<CommandSourceStack> arguments) {
        try {
            ServerLevel world = arguments.getSource().getLevel();
            Vec3 currentPos = arguments.getSource().getPosition();
            
            // On récupère toute la phrase et on la découpe
            String rawArgs = StringArgumentType.getString(arguments, "args");
            String[] parts = rawArgs.trim().split("\\s+"); // Découpe par espace

            if (parts.length < 7) {
                arguments.getSource().sendFailure(Component.translatable("commands.zone_cleaner.error.generic", "Format invalide. Utilisez : x1 y1 z1 x2 y2 z2 bloc"));
                return 0;
            }

            int minH = world.dimensionType().minY();
            int maxH = minH + world.dimensionType().height();

            double x1 = parseCoord(parts[0], currentPos.x, minH, maxH);
            double y1 = parseCoord(parts[1], currentPos.y, minH, maxH);
            double z1 = parseCoord(parts[2], currentPos.z, minH, maxH);
            
            double x2 = parseCoord(parts[3], currentPos.x, minH, maxH);
            double y2 = parseCoord(parts[4], currentPos.y, minH, maxH);
            double z2 = parseCoord(parts[5], currentPos.z, minH, maxH);

            String blockName = parts[6]; // Le bloc est le 7ème élément
            Block targetBlock = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(blockName)).orElse(Blocks.AIR);

            if (targetBlock == Blocks.AIR && !blockName.equals("minecraft:air") && !blockName.equals("air")) {
                arguments.getSource().sendFailure(Component.translatable("commands.zone_cleaner.error.block_not_found", blockName));
                return 0;
            }

            BlockPos minPos = new BlockPos((int)Math.min(x1, x2), (int)Math.min(y1, y2), (int)Math.min(z1, z2));
            BlockPos maxPos = new BlockPos((int)Math.max(x1, x2), (int)Math.max(y1, y2), (int)Math.max(z1, z2));

            long volume = (long) (maxPos.getX() - minPos.getX() + 1) * 
                          (long) (maxPos.getY() - minPos.getY() + 1) * 
                          (long) (maxPos.getZ() - minPos.getZ() + 1);

            arguments.getSource().sendSuccess(() -> Component.translatable("commands.zone_cleaner.start.block", blockName, volume), true);
            
            ServerPlayer player = arguments.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
            activeTasks.add(new BlockRemovalTask(world, minPos, maxPos, targetBlock, player));

        } catch (Exception e) {
            arguments.getSource().sendFailure(Component.translatable("commands.zone_cleaner.error.generic", e.getMessage()));
            e.printStackTrace();
        }
        return 1;
    }

    private static int executeUndo(CommandContext<CommandSourceStack> arguments) {
        if (undoHistoryStack.isEmpty()) {
            arguments.getSource().sendFailure(Component.translatable("commands.zone_cleaner.undo.empty"));
            return 0;
        }

        ServerLevel world = arguments.getSource().getLevel();
        ServerPlayer player = arguments.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
        List<BlockSnapshot> lastSnapshot = undoHistoryStack.removeLast();
        arguments.getSource().sendSuccess(() -> Component.translatable("commands.zone_cleaner.undo.start", lastSnapshot.size()), true);
        activeTasks.add(new UndoTask(world, lastSnapshot, player));
        return 1;
    }

    private static int executeEntityRemoval(CommandContext<CommandSourceStack> arguments) {
        try {
            ServerLevel world = arguments.getSource().getLevel();
            Vec3 currentPos = arguments.getSource().getPosition();
            
            String rawArgs = StringArgumentType.getString(arguments, "args");
            String[] parts = rawArgs.trim().split("\\s+");

            if (parts.length < 7) {
                arguments.getSource().sendFailure(Component.translatable("commands.zone_cleaner.error.generic", "Format invalide. Utilisez : x1 y1 z1 x2 y2 z2 entity"));
                return 0;
            }
            
            int minH = world.dimensionType().minY();
            int maxH = minH + world.dimensionType().height();

            double x1 = parseCoord(parts[0], currentPos.x, minH, maxH);
            double y1 = parseCoord(parts[1], currentPos.y, minH, maxH);
            double z1 = parseCoord(parts[2], currentPos.z, minH, maxH);
            
            double x2 = parseCoord(parts[3], currentPos.x, minH, maxH);
            double y2 = parseCoord(parts[4], currentPos.y, minH, maxH);
            double z2 = parseCoord(parts[5], currentPos.z, minH, maxH);
            
            String entityName = parts[6];
            EntityType<?> targetType = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(entityName)).orElse(null);

            if (targetType == null && !entityName.equals("all")) {
                arguments.getSource().sendFailure(Component.translatable("commands.zone_cleaner.error.entity_not_found", entityName));
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
            arguments.getSource().sendSuccess(() -> Component.translatable("commands.zone_cleaner.finish.entity", finalCount), true);

        } catch (Exception e) {
            arguments.getSource().sendFailure(Component.translatable("commands.zone_cleaner.error.generic", e.getMessage()));
            e.printStackTrace();
        }
        return 1;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (activeTasks.isEmpty()) return;
        Iterator<ITickableTask> iterator = activeTasks.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().processTick()) iterator.remove();
        }
    }

    private interface ITickableTask {
        boolean processTick();
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
        public boolean processTick() {
            int ops = 0;
            long startTime = System.nanoTime();

            while (ops < BLOCKS_PER_TICK) {
                if ((System.nanoTime() - startTime) > MAX_NANO_TIME_PER_TICK) break;

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

                ops++; processed++;
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
                owner.displayClientMessage(Component.translatable("commands.zone_cleaner.progress", p, processed, removed, remaining), true);
            }
        }

        private void finish() {
            if (!currentSessionUndo.isEmpty()) {
                if (undoHistoryStack.size() >= MAX_UNDO_HISTORY) undoHistoryStack.removeFirst();
                undoHistoryStack.add(currentSessionUndo);
            }
            if (owner != null && owner.connection != null) {
                owner.displayClientMessage(Component.translatable("commands.zone_cleaner.finish.block", removed), true);
                owner.sendSystemMessage(Component.translatable("commands.zone_cleaner.finish.chat", removed));
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
        public boolean processTick() {
            int ops = 0;
            long startTime = System.nanoTime();

            while (ops < BLOCKS_PER_TICK && index < total) {
                if ((System.nanoTime() - startTime) > MAX_NANO_TIME_PER_TICK) break;

                BlockSnapshot snap = snapshots.get(index);
                BlockPos pos = BlockPos.of(snap.posLong());
                world.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, true);
                world.setBlock(pos, snap.state(), 2);
                index++;
                ops++;
            }
            
            if (owner != null && owner.connection != null) {
                int p = (int) ((index * 100.0) / total);
                owner.displayClientMessage(Component.translatable("commands.zone_cleaner.undo.progress", p, index, total), true);
            }
            if (index >= total) {
                if (owner != null) owner.displayClientMessage(Component.translatable("commands.zone_cleaner.undo.finish"), true);
                return true;
            }
            return false;
        }
    }
}