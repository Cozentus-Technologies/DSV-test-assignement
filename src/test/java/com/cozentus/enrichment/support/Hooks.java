package com.cozentus.enrichment.support;

import io.cucumber.java.After;

/** SPEC 8.2: {@code @After} resets the bus. The context itself is per-scenario. */
public class Hooks {

    private final ScenarioContext context;

    public Hooks(ScenarioContext context) {
        this.context = context;
    }

    @After
    public void resetBus() {
        context.tearDown();
    }
}
