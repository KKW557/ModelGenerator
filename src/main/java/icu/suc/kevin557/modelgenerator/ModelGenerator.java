package icu.suc.kevin557.modelgenerator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

public class ModelGenerator implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger(ModelGenerator.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String JSON = ".json";

    private static final File DIR = new File(new File("generated"), "models");
    private static final File ENTITY_DIR = new File(DIR, "entity");

    private boolean flag;

    @SuppressWarnings("unchecked")
    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (flag) {
                return;
            }

            var dispatcher = client.getEntityRenderDispatcher();
            try {
                var field = dispatcher.getClass().getDeclaredField("renderers");
                field.setAccessible(true);
                var renderers = (Map<EntityType<?>, EntityRenderer<?, ?>>) field.get(dispatcher);
                if (generateEntityModels(renderers)) {
                    client.stop();
                    flag = true;
                }
            } catch (Exception e) {
                LOGGER.error("Error", e);
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
                Object model;

                if (renderer instanceof LivingEntityRenderer<?, ?, ?>) {
                    model = ((LivingEntityRenderer<?, ?, ?>) renderer).getModel();
                } else {
                    Field field = renderer.getClass().getDeclaredField("model");
                    field.setAccessible(true);
                    model = field.get(renderer);
                }

                var id = type.toShortString();
                var file = new File(ENTITY_DIR, id + JSON);

                if (createFile(file) || writeJson(file, model)) {
                    LOGGER.warn("Failed to generate entity model for '{}'", id);
                } else {
                    LOGGER.info("Generated entity model for '{}'", id);
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}
        });

        return true;
    }

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

    private static boolean createFile(@NotNull File file) {
        if (file.exists()) {
            return false;
        }

        try {
            file.createNewFile();
            return false;
        } catch (IOException e) {
            LOGGER.error("Could not create file: {}", file, e);
            return true;
        }
    }

    private static boolean writeJson(@NotNull File file, @NotNull Object object) {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(GSON.toJson(object));
            return false;
        } catch (IOException e) {
            LOGGER.error("Could not write file: {}", file, e);
            return true;
        }
    }
}
