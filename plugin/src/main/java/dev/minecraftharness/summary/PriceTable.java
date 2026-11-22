package dev.minecraftharness.summary;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;

/**
 * API prices in USD per million tokens, per model, read from config.yml. A model with no entry (or all
 * zeros) has no price, and then no cost is shown: nothing is guessed.
 */
public final class PriceTable {

    public record Price(double input, double output, double cacheRead, double cacheWrite) {
        boolean known() {
            return input > 0 || output > 0 || cacheRead > 0 || cacheWrite > 0;
        }
    }

    private final Map<String, Price> prices = new HashMap<>();

    public static PriceTable fromConfig(ConfigurationSection section) {
        PriceTable t = new PriceTable();
        if (section == null) return t;
        for (String model : section.getKeys(false)) {
            ConfigurationSection m = section.getConfigurationSection(model);
            if (m == null) continue;
            t.prices.put(model, new Price(m.getDouble("input", 0), m.getDouble("output", 0),
                m.getDouble("cacheRead", 0), m.getDouble("cacheWrite", 0)));
        }
        return t;
    }

    public PriceTable put(String model, Price p) {
        prices.put(model, p);
        return this;
    }

    /** @return the estimated cost in USD, or null when this model has no price. */
    public Double cost(String model, TurnStats.Tokens t) {
        Price p = prices.get(model);
        if (p == null || !p.known() || t == null || !t.known()) return null;
        return (t.input() * p.input + t.output() * p.output + t.cacheRead() * p.cacheRead
            + t.cacheWrite() * p.cacheWrite) / 1_000_000.0;
    }
}
