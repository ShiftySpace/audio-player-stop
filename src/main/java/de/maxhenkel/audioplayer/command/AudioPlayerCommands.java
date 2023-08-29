package de.maxhenkel.audioplayer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.maxhenkel.audioplayer.*;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.InstrumentItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.net.UnknownHostException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AudioPlayerCommands {

    public static final Pattern SOUND_FILE_PATTERN = Pattern.compile("^[a-z0-9_ -]+.((wav)|(mp3))$", Pattern.CASE_INSENSITIVE);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext ctx, Commands.CommandSelection environment) {
        LiteralArgumentBuilder<CommandSourceStack> literalBuilder = Commands.literal("audioplayer")
                .requires((commandSource) -> commandSource.hasPermission(Math.min(AudioPlayer.SERVER_CONFIG.uploadPermissionLevel.get(), AudioPlayer.SERVER_CONFIG.applyToItemPermissionLevel.get())));

        literalBuilder.executes(context -> {
            context.getSource().sendSuccess(() ->
                            Component.literal("Upload audio via Filebin ")
                                    .append(Component.literal("here").withStyle(style -> {
                                        return style
                                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/audioplayer upload"))
                                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to show more")));
                                    }).withStyle(ChatFormatting.GREEN))
                                    .append(".")
                    , false);
            context.getSource().sendSuccess(() ->
                            Component.literal("Upload audio with access to the servers file system ")
                                    .append(Component.literal("here").withStyle(style -> {
                                        return style
                                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/audioplayer serverfile"))
                                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to show more")));
                                    }).withStyle(ChatFormatting.GREEN))
                                    .append(".")
                    , false);
            context.getSource().sendSuccess(() ->
                            Component.literal("Upload audio from a URL ")
                                    .append(Component.literal("here").withStyle(style -> {
                                        return style
                                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/audioplayer url"))
                                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to show more")));
                                    }).withStyle(ChatFormatting.GREEN))
                                    .append(".")
                    , false);
            return 1;
        });

        literalBuilder.then(Commands.literal("upload")
                .requires((commandSource) -> commandSource.hasPermission(AudioPlayer.SERVER_CONFIG.uploadPermissionLevel.get()))
                .executes(filebinCommand())
        );

        literalBuilder.then(Commands.literal("filebin")
                .requires((commandSource) -> commandSource.hasPermission(AudioPlayer.SERVER_CONFIG.uploadPermissionLevel.get()))
                .executes(filebinCommand())
                .then(Commands.argument("id", UuidArgument.uuid())
                        .executes((context) -> {
                            UUID sound = UuidArgument.getUuid(context, "id");

                            new Thread(() -> {
                                try {
                                    context.getSource().sendSuccess(() -> Component.literal("Downloading sound, please wait..."), false);
                                    Filebin.downloadSound(context.getSource().getServer(), sound);
                                    context.getSource().sendSuccess(() -> sendUUIDMessage(sound, Component.literal("Successfully downloaded sound.")), false);
                                } catch (Exception e) {
                                    AudioPlayer.LOGGER.warn("{} failed to download a sound: {}", context.getSource().getTextName(), e.getMessage());
                                    context.getSource().sendFailure(Component.literal("Failed to download sound: %s".formatted(e.getMessage())));
                                }
                            }).start();

                            return 1;
                        }))
        );

        literalBuilder.then(Commands.literal("url")
                .requires((commandSource) -> commandSource.hasPermission(AudioPlayer.SERVER_CONFIG.uploadPermissionLevel.get()))
                .executes(context -> {
                    context.getSource().sendSuccess(() ->
                                    Component.literal("If you have a direct link to a ")
                                            .append(Component.literal(".mp3").withStyle(ChatFormatting.GRAY))
                                            .append(" or ")
                                            .append(Component.literal(".wav").withStyle(ChatFormatting.GRAY))
                                            .append(" file, enter the following command: ")
                                            .append(Component.literal("/audioplayer url <link-to-your-file>").withStyle(ChatFormatting.GRAY).withStyle(style -> {
                                                return style
                                                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/audioplayer url "))
                                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to fill in the command")));
                                            }))
                                            .append(".")
                            , false);
                    return 1;
                })
                .then(Commands.argument("url", StringArgumentType.string())
                        .executes((context) -> {
                            String url = StringArgumentType.getString(context, "url");
                            UUID sound = UUID.randomUUID();
                            new Thread(() -> {
                                try {
                                    context.getSource().sendSuccess(() -> Component.literal("Downloading sound, please wait..."), false);
                                    AudioManager.saveSound(context.getSource().getServer(), sound, url);
                                    context.getSource().sendSuccess(() -> sendUUIDMessage(sound, Component.literal("Successfully downloaded sound.")), false);
                                } catch (UnknownHostException e) {
                                    AudioPlayer.LOGGER.warn("{} failed to download a sound: {}", context.getSource().getTextName(), e.toString());
                                    context.getSource().sendFailure(Component.literal("Failed to download sound: Unknown host"));
                                } catch (UnsupportedAudioFileException e) {
                                    AudioPlayer.LOGGER.warn("{} failed to download a sound: {}", context.getSource().getTextName(), e.toString());
                                    context.getSource().sendFailure(Component.literal("Failed to download sound: Invalid file format"));
                                } catch (Exception e) {
                                    AudioPlayer.LOGGER.warn("{} failed to download a sound: {}", context.getSource().getTextName(), e.toString());
                                    context.getSource().sendFailure(Component.literal("Failed to download sound: %s".formatted(e.getMessage())));
                                }
                            }).start();

                            return 1;
                        }))
        );

        literalBuilder.then(Commands.literal("serverfile")
                .requires((commandSource) -> commandSource.hasPermission(AudioPlayer.SERVER_CONFIG.uploadPermissionLevel.get()))
                .executes(context -> {
                    context.getSource().sendSuccess(() ->
                                    Component.literal("Upload a ")
                                            .append(Component.literal(".mp3").withStyle(ChatFormatting.GRAY))
                                            .append(" or ")
                                            .append(Component.literal(".wav").withStyle(ChatFormatting.GRAY))
                                            .append(" file to ")
                                            .append(Component.literal(AudioManager.getUploadFolder().toAbsolutePath().toString()).withStyle(ChatFormatting.GRAY))
                                            .append(" on the server and run the command ")
                                            .append(Component.literal("/audioplayer serverfile \"yourfile.mp3\"").withStyle(ChatFormatting.GRAY).withStyle(style -> {
                                                return style
                                                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/audioplayer serverfile "))
                                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to fill in the command")));
                                            }))
                                            .append(".")
                            , false);
                    return 1;
                })
                .then(Commands.argument("filename", StringArgumentType.string())
                        .executes((context) -> {
                            String fileName = StringArgumentType.getString(context, "filename");
                            Matcher matcher = SOUND_FILE_PATTERN.matcher(fileName);
                            if (!matcher.matches()) {
                                context.getSource().sendFailure(Component.literal("Invalid file name! Valid characters are ")
                                        .append(Component.literal("A-Z").withStyle(ChatFormatting.GRAY))
                                        .append(", ")
                                        .append(Component.literal("0-9").withStyle(ChatFormatting.GRAY))
                                        .append(", ")
                                        .append(Component.literal("_").withStyle(ChatFormatting.GRAY))
                                        .append(" and ")
                                        .append(Component.literal("-").withStyle(ChatFormatting.GRAY))
                                        .append(". The name must also end in ")
                                        .append(Component.literal(".mp3").withStyle(ChatFormatting.GRAY))
                                        .append(" or ")
                                        .append(Component.literal(".wav").withStyle(ChatFormatting.GRAY))
                                        .append(".")
                                );
                                return 1;
                            }
                            UUID uuid = UUID.randomUUID();
                            new Thread(() -> {
                                Path file = AudioManager.getUploadFolder().resolve(fileName);
                                try {
                                    AudioManager.saveSound(context.getSource().getServer(), uuid, file);
                                    context.getSource().sendSuccess(() -> sendUUIDMessage(uuid, Component.literal("Successfully copied sound.")), false);
                                    context.getSource().sendSuccess(() -> Component.literal("Deleted temporary file ").append(Component.literal(fileName).withStyle(ChatFormatting.GRAY)).append("."), false);
                                } catch (NoSuchFileException e) {
                                    context.getSource().sendFailure(Component.literal("Could not find file ").append(Component.literal(fileName).withStyle(ChatFormatting.GRAY)).append("."));
                                } catch (Exception e) {
                                    AudioPlayer.LOGGER.warn("{} failed to copy a sound: {}", context.getSource().getTextName(), e.getMessage());
                                    context.getSource().sendFailure(Component.literal("Failed to copy sound: %s".formatted(e.getMessage())));
                                }
                            }).start();

                            return 1;
                        }))
        );

        literalBuilder.then(applyCommand(Commands.literal("musicdisc"), itemStack -> itemStack.getItem() instanceof RecordItem, "Music Disc", maxMusicDiscRange(), false));
        literalBuilder.then(applyCommand(Commands.literal("goathorn"), itemStack -> itemStack.getItem() instanceof InstrumentItem, "Goat Horn", maxGoatHornRange(), false));
        literalBuilder.then(bulkApplyCommand(Commands.literal("musicdisc_bulk"), itemStack -> itemStack.getItem() instanceof RecordItem, itemStack -> itemStack.getItem() instanceof BlockItem blockitem && blockitem.getBlock() instanceof ShulkerBoxBlock, "Shulker Box", maxMusicDiscRange()));
        literalBuilder.then(bulkApplyCommand(Commands.literal("goathorn_bulk"), itemStack -> itemStack.getItem() instanceof InstrumentItem, itemStack -> itemStack.getItem() instanceof BlockItem blockitem && blockitem.getBlock() instanceof ShulkerBoxBlock, "Shulker Box", maxGoatHornRange()));

        if (AudioPlayer.SERVER_CONFIG.announcerDiscsEnabled.get()) {
            literalBuilder.then(applyCommand(Commands.literal("musicdisc_announcer"), itemStack -> itemStack.getItem() instanceof RecordItem, "Music Disc", maxMusicDiscRange(), true));

            literalBuilder.then(Commands.literal("set_announcer")
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes((context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                ItemStack itemInHand = player.getItemInHand(InteractionHand.MAIN_HAND);
                                boolean enabled = BoolArgumentType.getBool(context, "enabled");

                                if (!(itemInHand.getItem() instanceof RecordItem)) {
                                    context.getSource().sendFailure(Component.literal("Invalid item"));
                                    return 1;
                                }
                                if (!itemInHand.hasTag()) {
                                    context.getSource().sendFailure(Component.literal("Item does not contain NBT data"));
                                    return 1;
                                }
                                CompoundTag tag = itemInHand.getTag();

                                if (tag == null) {
                                    return 1;
                                }

                                if (!tag.contains("CustomSound")) {
                                    context.getSource().sendFailure(Component.literal("Item does not have custom audio"));
                                    return 1;
                                }

                                tag.putBoolean("IsStaticCustomSound", enabled);

                                context.getSource().sendSuccess(() -> Component.literal("Set announcer " + (enabled ? "enabled" : "disabled")), false);

                                return 1;
                            }))));
        }

        literalBuilder.then(Commands.literal("clear")
                .executes((context) -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ItemStack itemInHand = player.getItemInHand(InteractionHand.MAIN_HAND);

                    if (!(itemInHand.getItem() instanceof RecordItem) && !(itemInHand.getItem() instanceof InstrumentItem)) {
                        context.getSource().sendFailure(Component.literal("Invalid item"));
                        return 1;
                    }

                    if (!itemInHand.hasTag()) {
                        context.getSource().sendFailure(Component.literal("Item does not contain NBT data"));
                        return 1;
                    }

                    CompoundTag tag = itemInHand.getTag();

                    if (tag == null) {
                        return 1;
                    }

                    if (!tag.contains("CustomSound")) {
                        context.getSource().sendFailure(Component.literal("Item does not have custom audio"));
                        return 1;
                    }

                    tag.remove("CustomSound");
                    tag.remove("CustomSoundRange");
                    tag.remove("IsStaticCustomSound");

                    if (itemInHand.getItem() instanceof InstrumentItem) {
                        tag.putString("instrument", "minecraft:ponder_goat_horn");
                    }

                    tag.remove(ItemStack.TAG_DISPLAY);
                    tag.remove("HideFlags");

                    context.getSource().sendSuccess(() -> Component.literal("Successfully cleared item"), false);
                    return 1;
                })
        );

        literalBuilder.then(Commands.literal("id")
                .executes((context) -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ItemStack itemInHand = player.getItemInHand(InteractionHand.MAIN_HAND);

                    if (!(itemInHand.getItem() instanceof RecordItem) && !(itemInHand.getItem() instanceof InstrumentItem)) {
                        context.getSource().sendFailure(Component.literal("Invalid item"));
                        return 1;
                    }

                    if (!itemInHand.hasTag()) {
                        context.getSource().sendFailure(Component.literal("Item does not have custom audio"));
                        return 1;
                    }

                    CompoundTag tag = itemInHand.getTag();

                    if (tag == null) {
                        return 1;
                    }

                    if (!tag.contains("CustomSound")) {
                        context.getSource().sendFailure(Component.literal("Item does not have custom audio"));
                        return 1;
                    }

                    context.getSource().sendSuccess(() -> sendUUIDMessage(tag.getUUID("CustomSound"), Component.literal("Successfully extracted sound ID.")), false);
                    return 1;
                })
        );

        literalBuilder.then(Commands.literal("play")
                .requires((commandSource) -> commandSource.hasPermission(AudioPlayer.SERVER_CONFIG.playCommandPermissionLevel.get()))
                .then(Commands.argument("sound", UuidArgument.uuid())
                        .then(Commands.argument("location", Vec3Argument.vec3())
                                .then(Commands.argument("range", FloatArgumentType.floatArg(0F, Float.MAX_VALUE))
                                        .executes(context -> {
                                            return play(
                                                    context,
                                                    UuidArgument.getUuid(context, "sound"),
                                                    Vec3Argument.getVec3(context, "location"),
                                                    FloatArgumentType.getFloat(context, "range")
                                            );
                                        })))));
        
       literalBuilder.then(Commands.literal("stop")
                        .requires((commandSource) -> commandSource.hasPermission(AudioPlayer.SERVER_CONFIG.playCommandPermissionLevel.get()))
                        .then(Commands.argument("sound", UuidArgument.uuid())
                                .executes(context -> stop(context, UuidArgument.getUuid(context, "sound")))
    )
);

        dispatcher.register(literalBuilder);
    }

        private static int stop(CommandContext<CommandSourceStack> context, UUID sound) {
                UUID channelID = PlayerManager.instance().findChannelID(sound);
            
                if (channelID != null) {
                    PlayerManager.instance().stop(channelID);
                                context.getSource()
                                                .sendSuccess(() -> Component.literal(
                                                                "Successfully stopped %s.".formatted(sound)),
                                                                false);
                                return 1;
                        } else {
                                context.getSource().sendFailure(Component
                                                .literal("Failed to stop, Could not find %s".formatted(sound)));
                        }
                        return 0;
            }

    private static int play(CommandContext<CommandSourceStack> context, UUID sound, Vec3 location, float range) {
        @Nullable ServerPlayer player = context.getSource().getPlayer();
        VoicechatServerApi api = Plugin.voicechatServerApi;
        if (api == null) {
            return 0;
        }
        PlayerManager.instance().playLocational(
                api,
                context.getSource().getLevel(),
                location,
                sound,
                player,
                range,
                null,
                Integer.MAX_VALUE
        );
        context.getSource().sendSuccess(() -> Component.literal("Successfully played %s".formatted(sound)), false);
        return 1;
    }

    public static MutableComponent sendUUIDMessage(UUID soundID, MutableComponent component) {
        return component.append(" ")
                .append(ComponentUtils.wrapInSquareBrackets(Component.literal("Copy ID"))
                        .withStyle(style -> {
                            return style
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, soundID.toString()))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Copy sound ID")));
                        })
                        .withStyle(ChatFormatting.GREEN)
                )
                .append(" ")
                .append(ComponentUtils.wrapInSquareBrackets(Component.literal("Put on music disc"))
                        .withStyle(style -> {
                            return style
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/audioplayer musicdisc %s".formatted(soundID.toString())))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Put the sound on a music disc")));
                        })
                        .withStyle(ChatFormatting.GREEN)
                ).append(" ")
                .append(ComponentUtils.wrapInSquareBrackets(Component.literal("Put on goat horn"))
                        .withStyle(style -> {
                            return style
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/audioplayer goathorn %s".formatted(soundID.toString())))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Put the sound on a goat horn")));
                        })
                        .withStyle(ChatFormatting.GREEN)
                );
    }

    private static Command<CommandSourceStack> filebinCommand() {
        return (context) -> {
            UUID uuid = UUID.randomUUID();
            String uploadURL = Filebin.getBin(uuid);

            MutableComponent msg = Component.literal("Click ")
                    .append(Component.literal("this link")
                            .withStyle(style -> {
                                return style
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, uploadURL))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to open")));
                            })
                            .withStyle(ChatFormatting.GREEN)
                    )
                    .append(" and upload your sound as ")
                    .append(Component.literal("mp3").withStyle(ChatFormatting.GRAY))
                    .append(" or ")
                    .append(Component.literal("wav").withStyle(ChatFormatting.GRAY))
                    .append(".\n")
                    .append("Once you have uploaded the file, click ")
                    .append(Component.literal("here")
                            .withStyle(style -> {
                                return style
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/audioplayer filebin " + uuid))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to confirm upload")));
                            })
                            .withStyle(ChatFormatting.GREEN)
                    )
                    .append(".");

            context.getSource().sendSuccess(() -> msg, false);

            return 1;
        };
    }

    private static LiteralArgumentBuilder<CommandSourceStack> applyCommand(LiteralArgumentBuilder<CommandSourceStack> builder, Predicate<ItemStack> validator, String itemTypeName, FloatArgumentType argumentType, boolean isStatic) {
        RequiredArgumentBuilder<CommandSourceStack, UUID> commandWithSound = Commands.argument("sound", UuidArgument.uuid());

        commandWithSound.executes((context) -> {
            apply(context, validator, itemTypeName, null, null, isStatic);
            return 1;
        });

        RequiredArgumentBuilder<CommandSourceStack, Float> commandWithRange = Commands.argument("range", argumentType);
        commandWithRange.executes(context -> {
            apply(context, validator, itemTypeName, null, FloatArgumentType.getFloat(context, "range"), isStatic);
            return 1;
        });
        commandWithSound.then(commandWithRange);

        RequiredArgumentBuilder<CommandSourceStack, String> commandWithCustomName = Commands.argument("custom_name", StringArgumentType.string());
        commandWithCustomName.executes(context -> {
            apply(context, validator, itemTypeName, StringArgumentType.getString(context, "custom_name"), null, isStatic);
            return 1;
        });
        commandWithCustomName.then(Commands.argument("range", argumentType).executes(context -> {
            apply(context, validator, itemTypeName, StringArgumentType.getString(context, "custom_name"), FloatArgumentType.getFloat(context, "range"), isStatic);
            return 1;
        }));
        commandWithSound.then(commandWithCustomName);

        return builder
                .requires((commandSource) -> commandSource.hasPermission(AudioPlayer.SERVER_CONFIG.applyToItemPermissionLevel.get()))
                .then(commandWithSound);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> bulkApplyCommand(LiteralArgumentBuilder<CommandSourceStack> builder, Predicate<ItemStack> itemValidator, Predicate<ItemStack> containerValidator, String itemTypeName, FloatArgumentType argumentType) {
        RequiredArgumentBuilder<CommandSourceStack, UUID> commandWithSound = Commands.argument("sound", UuidArgument.uuid());

        commandWithSound.executes((context) -> {
            applyBulk(context, itemValidator, containerValidator, itemTypeName, null, null);
            return 1;
        });

        RequiredArgumentBuilder<CommandSourceStack, Float> commandWithRange = Commands.argument("range", argumentType);
        commandWithRange.executes(context -> {
            applyBulk(context, itemValidator, containerValidator, itemTypeName, null, FloatArgumentType.getFloat(context, "range"));
            return 1;
        });
        commandWithSound.then(commandWithRange);

        RequiredArgumentBuilder<CommandSourceStack, String> commandWithCustomName = Commands.argument("custom_name", StringArgumentType.string());
        commandWithCustomName.executes(context -> {
            applyBulk(context, itemValidator, containerValidator, itemTypeName, StringArgumentType.getString(context, "custom_name"), null);
            return 1;
        });
        commandWithCustomName.then(Commands.argument("range", argumentType).executes(context -> {
            applyBulk(context, itemValidator, containerValidator, itemTypeName, StringArgumentType.getString(context, "custom_name"), FloatArgumentType.getFloat(context, "range"));
            return 1;
        }));
        commandWithSound.then(commandWithCustomName);

        return builder
                .requires((commandSource) -> commandSource.hasPermission(AudioPlayer.SERVER_CONFIG.applyToItemPermissionLevel.get()))
                .then(commandWithSound);
    }


    private static void apply(CommandContext<CommandSourceStack> context, Predicate<ItemStack> validator, String itemTypeName, @Nullable String customName, @Nullable Float range, boolean isStatic) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        UUID sound = UuidArgument.getUuid(context, "sound");
        ItemStack itemInHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (validator.test(itemInHand)) {
            renameItem(context, itemInHand, sound, customName, range, isStatic);
        } else {
            context.getSource().sendFailure(Component.literal("You don't have a %s in your main hand".formatted(itemTypeName)));
        }
    }

    private static void applyBulk(CommandContext<CommandSourceStack> context, Predicate<ItemStack> itemValidator, Predicate<ItemStack> containerValidator, String itemTypeName, @Nullable String customName, @Nullable Float range) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        UUID sound = UuidArgument.getUuid(context, "sound");
        ItemStack itemInHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (containerValidator.test(itemInHand)) {
            processShulker(context, itemInHand, itemValidator, itemTypeName, sound, customName, range);
        } else {
            context.getSource().sendFailure(Component.literal("You don't have a %s in your main hand".formatted(itemTypeName)));
        }
    }

    private static void processShulker(CommandContext<CommandSourceStack> context, ItemStack itemInHand, Predicate<ItemStack> itemValidator, String itemTypeName, UUID soundID, @Nullable String name, @Nullable Float range) {
        ListTag shulkerContents = itemInHand.getOrCreateTagElement(BlockItem.BLOCK_ENTITY_TAG).getList(ShulkerBoxBlockEntity.ITEMS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < shulkerContents.size(); i++) {
            CompoundTag currentItem = shulkerContents.getCompound(i);
            ItemStack itemStack = ItemStack.of(currentItem);
            if (itemValidator.test(itemStack)) {
                renameItem(context, itemStack, soundID, name, range, false);
                currentItem.put("tag", itemStack.getOrCreateTag());
            }
        }
        itemInHand.getOrCreateTagElement(BlockItem.BLOCK_ENTITY_TAG).put(ShulkerBoxBlockEntity.ITEMS_TAG, shulkerContents);
        context.getSource().sendSuccess(() -> Component.literal("Successfully updated %s contents".formatted(itemTypeName)), false);
    }

    private static void renameItem(CommandContext<CommandSourceStack> context, ItemStack stack, UUID soundID, @Nullable String name, @Nullable Float range, boolean isStatic) {
        CompoundTag tag = stack.getOrCreateTag();

        tag.putUUID("CustomSound", soundID);

        if (range != null) {
            tag.putFloat("CustomSoundRange", range);
        }

        if (isStatic) {
            tag.putBoolean("IsStaticCustomSound", true);
        }

        if (tag.contains("instrument", Tag.TAG_STRING)) {
            tag.putString("instrument", "");
        }

        ListTag lore = new ListTag();
        if (name != null) {
            lore.add(0, StringTag.valueOf(Component.Serializer.toJson(Component.literal(name).withStyle(style -> style.withItalic(false)).withStyle(ChatFormatting.GRAY))));
        }

        CompoundTag display = new CompoundTag();
        display.put(ItemStack.TAG_LORE, lore);
        tag.put(ItemStack.TAG_DISPLAY, display);

        tag.putInt("HideFlags", ItemStack.TooltipPart.ADDITIONAL.getMask());

        context.getSource().sendSuccess(() -> Component.literal("Successfully updated ").append(stack.getHoverName()), false);
    }

    private static FloatArgumentType maxMusicDiscRange() {
        return FloatArgumentType.floatArg(1F, AudioPlayer.SERVER_CONFIG.maxMusicDiscRange.get());
    }

    private static FloatArgumentType maxGoatHornRange() {
        return FloatArgumentType.floatArg(1F, AudioPlayer.SERVER_CONFIG.maxGoatHornRange.get());
    }

}
