package dev.donut.client.autobuild;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads coin prices out of auction-house tooltips. A purchase is only attempted when a price can
 * be parsed and its per-item cost stays inside the configured maximum, so unpriced or bait
 * listings are never bought.
 */
public final class AhPriceParser {
    /** $1,234 / 1,234 coins / 2.5k / 3m / 1b / bare amounts after a price keyword. */
    private static final Pattern MONEY = Pattern.compile(
            "([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbKMB])?(?![0-9a-zA-Z])");
    private static final Pattern BUY_WORD = Pattern.compile(
            "(?i)\\b(price|buyout|buy|cost|purchase|bin|now)\\b");
    private static final Pattern BID_WORD = Pattern.compile("(?i)\\bbid\\b");
    private static final Pattern CURRENCY = Pattern.compile("(?i)(\\$|coin|token|gem)");

    private AhPriceParser() { }

    /** Parses the first sensible price from tooltip lines; empty when no trustworthy price exists. */
    public static OptionalDouble parse(List<String> lines) {
        OptionalDouble keyword = OptionalDouble.empty();
        OptionalDouble currency = OptionalDouble.empty();
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) continue;
            boolean buy = BUY_WORD.matcher(line).find();
            boolean bid = BID_WORD.matcher(line).find();
            OptionalDouble value = firstAmount(line);
            if (value.isEmpty()) continue;
            if (buy && !bid && keyword.isEmpty()) keyword = value;
            else if (CURRENCY.matcher(line).find() && currency.isEmpty()) currency = value;
        }
        return keyword.isPresent() ? keyword : currency;
    }

    /** Price per item for a listing; stacks divide the total so bulk listings compare fairly. */
    public static double unitPrice(double total, int count) {
        return total / Math.max(1, count);
    }

    private static OptionalDouble firstAmount(String line) {
        Matcher matcher = MONEY.matcher(line);
        while (matcher.find()) {
            double value = parseNumber(matcher.group(1));
            if (value <= 0) continue;
            String suffix = matcher.group(2);
            if (suffix != null) {
                switch (Character.toLowerCase(suffix.charAt(0))) {
                    case 'k' -> value *= 1_000;
                    case 'm' -> value *= 1_000_000;
                    case 'b' -> value *= 1_000_000_000;
                    default -> { }
                }
            }
            return OptionalDouble.of(value);
        }
        return OptionalDouble.empty();
    }

    private static double parseNumber(String text) {
        try {
            return Double.parseDouble(text.replace(",", "").replace("_", "").toLowerCase(Locale.ROOT));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
