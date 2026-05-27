package com.tiger.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record IntervalMetadata(String code, Integer seconds, List<String> aliases) {
    private static final Pattern INTERVAL_PATTERN =
            Pattern.compile("(?i)(?<![a-z0-9])(\\d{1,3})\\s*(m|min|mins|minute|minutes|h|hr|hour|hours|d|day|days)(?![a-z0-9])");

    public static Optional<IntervalMetadata> fromText(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = INTERVAL_PATTERN.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        int amount = Integer.parseInt(matcher.group(1));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        return switch (unit.charAt(0)) {
            case 'm' -> Optional.of(minutes(amount));
            case 'h' -> Optional.of(hours(amount));
            case 'd' -> Optional.of(days(amount));
            default -> Optional.empty();
        };
    }

    private static IntervalMetadata minutes(int amount) {
        return new IntervalMetadata(
                amount + "m",
                amount * 60,
                List.of(
                        amount + "m",
                        amount + " min",
                        amount + " mins",
                        amount + " minute",
                        amount + " minutes"));
    }

    private static IntervalMetadata hours(int amount) {
        return new IntervalMetadata(
                amount + "h",
                amount * 3600,
                List.of(amount + "h", amount + " hour", amount + " hours", "hourly"));
    }

    private static IntervalMetadata days(int amount) {
        return new IntervalMetadata(
                amount + "d",
                amount * 86400,
                List.of(amount + "d", amount + " day", amount + " days", "daily"));
    }
}
