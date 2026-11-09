package com.example.pearlcatch.client;

import net.fabricmc.api.ClientModInitializer;

/** Entrypoint client: registra tasti, tick handler e comandi. */
public class PearlCatchClient implements ClientModInitializer {
    public static final String MOD_ID = "pearlcatch";

    @Override
    public void onInitializeClient() {
        PearlCatchInput.init();
    }
}
