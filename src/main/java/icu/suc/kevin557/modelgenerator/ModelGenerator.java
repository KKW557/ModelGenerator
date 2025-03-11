package icu.suc.kevin557.modelgenerator;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.SkullModelBase;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.HangingSignRenderer;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.properties.WoodType;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public class ModelGenerator implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger(ModelGenerator.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String JSON = ".json";

    private static final File DIR = new File(new File("generated"), "models");
    private static final File ENTITY_DIR = new File(DIR, "entity");
    private static final File BLOCK_ENTITY_DIR = new File(DIR, "block_entity");

    private boolean entityRender;
    private boolean blockEntityRender;

    private static boolean createDirectory(@NotNull File directory) {
        if (directory.exists()) {
            return false;
        }

        if (directory.mkdirs()) {
            return false;
        }

        LOGGER.error("Could not create directory: {}", directory);
        return true;
    }

    private static boolean writeJson(@NotNull File file, @NotNull Object object) {
        try (var writer = new FileWriter(file)) {
            writer.write(GSON.toJson(object));
            return false;
        }
        catch (IOException e) {
            LOGGER.error("Could not write file: {}", file, e);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (entityRender && blockEntityRender) {
                return;
            }

            try {
                if (!entityRender) {
                    var dispatcher = client.getEntityRenderDispatcher();
                    var field = dispatcher.getClass().getDeclaredField("renderers");
                    field.setAccessible(true);
                    var renderers = (Map<EntityType<?>, EntityRenderer<?, ?>>) field.get(dispatcher);
                    if (generateEntityModels(renderers)) {
                        entityRender = true;
                    }
                }
                if (!blockEntityRender) {
                    var dispatcher = client.getBlockEntityRenderDispatcher();
                    var field = dispatcher.getClass().getDeclaredField("renderers");
                    field.setAccessible(true);
                    var renderers = (Map<BlockEntityType<?>, BlockEntityRenderer<?>>) field.get(dispatcher);
                    if (generateBlockEntityModels(renderers)) {
                        blockEntityRender = true;
                    }
                }
                if (entityRender && blockEntityRender) {
                    client.stop();
                }
            }
            catch (Exception e) {
                LOGGER.error("Failed to generate", e);
            }
        });
    }

    private boolean generateEntityModels(@NotNull Map<EntityType<?>, EntityRenderer<?, ?>> renderers) {
        if (renderers.isEmpty()) {
            return false;
        }

        if (createDirectory(ENTITY_DIR)) {
            return true;
        }

        renderers.forEach((type, renderer) -> {
            try {
                var id = type.toShortString();
                var file = new File(ENTITY_DIR, id + JSON);

                Object model;

                if (renderer instanceof LivingEntityRenderer<?, ?, ?>) {
                    model = ((LivingEntityRenderer<?, ?, ?>) renderer).getModel();
                } else {
                    Field field = renderer.getClass().getDeclaredField("model");
                    field.setAccessible(true);
                    model = field.get(renderer);
                }

                if (writeJson(file, model)) {
                    LOGGER.warn("Failed to generate entity model for '{}'", id);
                }
                else {
                    LOGGER.info("Generated entity model for '{}'", id);
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}
        });

        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean generateBlockEntityModels(@NotNull Map<BlockEntityType<?>, BlockEntityRenderer<?>> renderers) {
        if (renderers.isEmpty()) {
            return false;
        }

        if (createDirectory(BLOCK_ENTITY_DIR)) {
            return true;
        }

        renderers.forEach((type, renderer) -> {
            var id = Objects.requireNonNull(BlockEntityType.getKey(type)).getPath();
            var file = new File(BLOCK_ENTITY_DIR, id + JSON);

            Map<String, Model> object = Maps.newHashMap();

            try {
                switch (renderer) {
                    case SignRenderer _ -> {
                        var field = renderer.getClass().getDeclaredField("signModels");
                        field.setAccessible(true);
                        Map<WoodType, Object> map = (Map<WoodType, Object>) field.get(renderer);
                        map.forEach((key, models) -> {
                            var name = key.name();
                            for (var model : models.getClass().getDeclaredFields()) {
                                model.setAccessible(true);
                                try {
                                    object.put(name + '/' + model.getName(), (Model) model.get(models));
                                } catch (IllegalAccessException ignored) {
                                }
                            }
                        });
                    }
                    case HangingSignRenderer _ -> {
                        var field = renderer.getClass().getDeclaredField("hangingSignModels");
                        field.setAccessible(true);
                        Map<HangingSignRenderer.ModelKey, Model> map = (Map<HangingSignRenderer.ModelKey, Model>) field.get(renderer);
                        map.forEach((key, model) -> object.put(key.woodType().name() + '/' + key.attachmentType().getSerializedName(), model));
                    }
                    case SkullBlockRenderer _ -> {
                        var field = renderer.getClass().getDeclaredField("modelByType");
                        field.setAccessible(true);
                        var modelByType = (Function<SkullBlock.Type, SkullModelBase>) field.get(renderer);
                        for (var value : SkullBlock.Types.values()) {
                            object.put(value.getSerializedName(), modelByType.apply(value));
                        }
                    }
                    default -> {
                        for (var field : renderer.getClass().getDeclaredFields()) {
                            field.setAccessible(true);
                            try {
                                object.put(field.getName(), (Model) field.get(renderer));
                            } catch (ClassCastException ignored) {
                            }
                        }
                    }
                }
            }
            catch (NullPointerException | IllegalAccessException ignored) {}
            catch (Exception e) {
                LOGGER.warn("Failed to generate block entity model for '{}'", id, e);
            }

            if (writeJson(file, object)) {
                LOGGER.warn("Failed to generate block entity model for '{}'", id);
            }
            else {
                LOGGER.info("Generated block entity model for '{}'", id);
            }
        });

        return true;
    }
}
