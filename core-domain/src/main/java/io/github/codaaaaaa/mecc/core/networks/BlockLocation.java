package io.github.codaaaaaa.mecc.core.networks;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A block position in a dimension, independent of any Minecraft class.
 *
 * @param dimension dimension resource location, e.g. {@code minecraft:overworld}
 */
public record BlockLocation(String dimension, int x, int y, int z) implements Comparable<BlockLocation> {
    private static final Pattern DIMENSION = Pattern.compile("^[a-z0-9_.\\-]+:[a-z0-9_.\\-/]+$");
    private static final Pattern KEY = Pattern.compile("^(.+)@(-?\\d{1,9}),(-?\\d{1,9}),(-?\\d{1,9})$");

    public BlockLocation {
        Objects.requireNonNull(dimension, "dimension");
    }

    /** Stable textual form, e.g. {@code minecraft:overworld@12,64,-30}. */
    public String key() {
        return dimension + "@" + x + "," + y + "," + z;
    }

    public static Optional<BlockLocation> parseKey(String key) {
        if (key == null || key.length() > 256) {
            return Optional.empty();
        }
        Matcher matcher = KEY.matcher(key);
        if (!matcher.matches() || !DIMENSION.matcher(matcher.group(1)).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BlockLocation(matcher.group(1),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(BlockLocation other) {
        int result = dimension.compareTo(other.dimension);
        if (result == 0) result = Integer.compare(x, other.x);
        if (result == 0) result = Integer.compare(y, other.y);
        if (result == 0) result = Integer.compare(z, other.z);
        return result;
    }

    @Override
    public String toString() {
        return key();
    }
}
