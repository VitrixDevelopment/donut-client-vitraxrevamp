package dev.donut.client.namehider;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes whole account names without discarding the styling around them.
 * Adapted from Name Hider 1.0.3 (CC0-1.0); see licenses/name-hider-CC0-1.0.txt.
 */
public final class SidebarTextSanitizer {
    private static final String REPLACEMENT = "";

    private SidebarTextSanitizer() { }

    public static Component replaceUsername(Component text, String username) {
        if (username == null || username.isEmpty()) return text;

        List<StyledSegment> segments = flatten(text);
        StringBuilder plain = new StringBuilder();
        for (StyledSegment segment : segments) plain.append(segment.text());
        List<Match> matches = findMatches(plain, username);
        if (matches.isEmpty()) return text;

        MutableComponent result = Component.empty();
        int cursor = 0;
        for (Match match : matches) {
            appendRange(result, segments, cursor, match.start());
            result.append(Component.literal(REPLACEMENT).setStyle(styleAt(segments, match.start())));
            cursor = match.end();
        }
        appendRange(result, segments, cursor, plain.length());
        return result;
    }

    private static List<StyledSegment> flatten(Component text) {
        List<StyledSegment> segments = new ArrayList<>();
        int offset = 0;
        for (Component part : text.toFlatList()) {
            String value = part.getString();
            if (value.isEmpty()) continue;
            segments.add(new StyledSegment(offset, value, part.getStyle()));
            offset += value.length();
        }
        return segments;
    }

    private static List<Match> findMatches(CharSequence text, String username) {
        List<Match> matches = new ArrayList<>();
        int length = username.length();
        for (int start = 0; start <= text.length() - length;) {
            int end = start + length;
            boolean leftBoundary = start == 0 || !isUsernameCharacter(text.charAt(start - 1));
            boolean rightBoundary = end == text.length() || !isUsernameCharacter(text.charAt(end));
            if (leftBoundary && rightBoundary && regionMatchesIgnoreCase(text, start, username)) {
                matches.add(new Match(start, end));
                start = end;
            } else {
                start++;
            }
        }
        return matches;
    }

    private static boolean regionMatchesIgnoreCase(CharSequence text, int start, String username) {
        for (int i = 0; i < username.length(); i++) {
            char actual = text.charAt(start + i), expected = username.charAt(i);
            if (actual != expected && Character.toLowerCase(actual) != Character.toLowerCase(expected)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUsernameCharacter(char value) {
        return value == '_' || value >= '0' && value <= '9'
                || value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private static Style styleAt(List<StyledSegment> segments, int index) {
        for (StyledSegment segment : segments) {
            if (index >= segment.start() && index < segment.end()) return segment.style();
        }
        return Style.EMPTY;
    }

    private static void appendRange(MutableComponent output, List<StyledSegment> segments, int start, int end) {
        if (start >= end) return;
        for (StyledSegment segment : segments) {
            if (segment.end() <= start) continue;
            if (segment.start() >= end) break;
            int from = Math.max(start, segment.start()) - segment.start();
            int to = Math.min(end, segment.end()) - segment.start();
            output.append(Component.literal(segment.text().substring(from, to)).setStyle(segment.style()));
        }
    }

    private record Match(int start, int end) { }

    private record StyledSegment(int start, String text, Style style) {
        int end() { return start + text.length(); }
    }
}
