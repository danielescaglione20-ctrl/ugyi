package com.example.pearlcatch.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * Gestione tasti + macro "Pearl Catch".
 *
 * GESTIONE DEL RITARDO (anti-desync):
 * - Non usiamo pacchetti manuali: usiamo ClientPlayerInteractionManager#interactItem, che
 *   internamente chiama syncSelectedSlot() e POI invia il pacchetto di uso item. Quindi l'ordine
 *   dei pacchetti verso il server è sempre: [cambio slot] -> [uso item]. Nessuna desync dello slot.
 * - Pearl e Wind Charge sono item DIVERSI, quindi i rispettivi cooldown (pearl 20 tick,
 *   wind charge 10 tick) non si bloccano a vicenda. Controlliamo comunque i cooldown prima di
 *   partire, così la sequenza non parte "a metà" (pearl lanciata e wind charge rifiutata).
 * - delayTicks = 0: entrambi i lanci nello stesso tick (il server li processa in ordine).
 *   delayTicks >= 1: la wind charge parte dopo N tick client, utile se il server/ping rifiuta
 *   due uso-item nello stesso tick. Il default è 1 (più stabile).
 * - Il pitch usato per il lancio viene inviato nel pacchetto di uso item (1.21.2+), quindi
 *   cambiarlo solo per un istante e ripristinarlo subito non causa rubber-banding né flicker.
 */
public final class PearlCatchInput {
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of(PearlCatchClient.MOD_ID, "main"));

    private static KeyBinding triggerKey;
    private static KeyBinding toggleKey;

    // Impostazioni (modificabili con /pearlcatch)
    private static boolean enabled = true;
    private static int delayTicks = 1;          // 0..10
    private static float windPitchOffset = 0f;  // gradi da sommare al pitch per la wind charge

    // Stato della sequenza
    private enum Phase { IDLE, WAIT_WIND }
    private static Phase phase = Phase.IDLE;
    private static int ticksLeft;
    private static int originalSlot;
    private static int windSlot;

    private PearlCatchInput() {}

    public static void init() {
        triggerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.pearlcatch.trigger", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.pearlcatch.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(PearlCatchInput::onTick);
        registerCommands();
    }

    // ---------------------------------------------------------------- tick

    private static void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) {
            phase = Phase.IDLE;
            return;
        }

        while (toggleKey.wasPressed()) {
            setEnabled(player, !enabled);
        }

        while (triggerKey.wasPressed()) {
            if (enabled && phase == Phase.IDLE) {
                startSequence(client, player);
            }
        }

        if (phase == Phase.WAIT_WIND && --ticksLeft <= 0) {
            throwWindCharge(client, player);
        }
    }

    // ------------------------------------------------------------ sequenza

    private static void startSequence(MinecraftClient client, ClientPlayerEntity player) {
        int pearlSlot = findInHotbar(player, Items.ENDER_PEARL);
        int wind = findInHotbar(player, Items.WIND_CHARGE);

        if (pearlSlot < 0 || wind < 0) {
            msg(player, "Serve una Ender Pearl e una Wind Charge nella hotbar!");
            return;
        }
        // Se uno dei due è in cooldown non partiamo: evitiamo sequenze incomplete.
        if (player.getItemCooldownManager().isCoolingDown(player.getInventory().getStack(pearlSlot))
                || player.getItemCooldownManager().isCoolingDown(player.getInventory().getStack(wind))) {
            msg(player, "Item in cooldown.");
            return;
        }

        originalSlot = player.getInventory().getSelectedSlot();
        windSlot = wind;

        // 1) Seleziona la pearl e lanciala. interactItem sincronizza lo slot prima dell'uso.
        player.getInventory().setSelectedSlot(pearlSlot);
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);

        // 2) Wind charge: subito (stesso tick) oppure dopo delayTicks.
        if (delayTicks <= 0) {
            throwWindCharge(client, player);
        } else {
            phase = Phase.WAIT_WIND;
            ticksLeft = delayTicks;
        }
    }

    private static void throwWindCharge(MinecraftClient client, ClientPlayerEntity player) {
        phase = Phase.IDLE;

        // Rivalida lo slot: l'inventario potrebbe essere cambiato durante l'attesa.
        if (!player.getInventory().getStack(windSlot).isOf(Items.WIND_CHARGE)) {
            player.getInventory().setSelectedSlot(originalSlot);
            msg(player, "Wind Charge non trovata, sequenza annullata.");
            return;
        }

        player.getInventory().setSelectedSlot(windSlot);

        // Pitch temporaneo: finisce nel pacchetto di uso item, poi lo ripristiniamo.
        float oldPitch = player.getPitch();
        player.setPitch(MathHelper.clamp(oldPitch + windPitchOffset, -90f, 90f));
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.setPitch(oldPitch);
        player.swingHand(Hand.MAIN_HAND);

        // Torna allo slot originale (verrà sincronizzato al prossimo uso/tick).
        player.getInventory().setSelectedSlot(originalSlot);
    }

    private static int findInHotbar(ClientPlayerEntity player, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(item)) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------- comandi

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("pearlcatch")
                        .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                            setEnabled(ctx.getSource().getPlayer(), !enabled);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("delay")
                                .then(ClientCommandManager.argument("ticks", IntegerArgumentType.integer(0, 10))
                                        .executes(ctx -> {
                                            delayTicks = IntegerArgumentType.getInteger(ctx, "ticks");
                                            ctx.getSource().sendFeedback(Text.literal("PearlCatch delay: " + delayTicks + " tick"));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("pitch")
                                .then(ClientCommandManager.argument("degrees", FloatArgumentType.floatArg(-90f, 90f))
                                        .executes(ctx -> {
                                            windPitchOffset = FloatArgumentType.getFloat(ctx, "degrees");
                                            ctx.getSource().sendFeedback(Text.literal("PearlCatch pitch offset: " + windPitchOffset + "°"));
                                            return 1;
                                        })))));
    }

    private static void setEnabled(ClientPlayerEntity player, boolean value) {
        enabled = value;
        msg(player, "PearlCatch " + (enabled ? "ON" : "OFF"));
    }

    private static void msg(ClientPlayerEntity player, String text) {
        player.sendMessage(Text.literal(text), true); // action bar
    }
}
