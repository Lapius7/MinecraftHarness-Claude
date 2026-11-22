package dev.minecraftharness.agent;

import java.util.function.BooleanSupplier;

/** Everything a backend needs to run one player prompt. */
public record TurnInput(
    String playerName,
    String userMessage,
    boolean filmMode,
    String reasoningLevel,
    HarnessAgent.Session session,
    ToolHost.Turn turn,
    BooleanSupplier cancelled
) {}
