package com.dating.match.constant;

import lombok.Getter;

/**
 * Swipe direction enum matching proto SwipeDirection and DB direction column.
 */
@Getter
public enum SwipeDirectionEnum {
    LEFT(1, "LEFT"),
    RIGHT(2, "RIGHT"),
    SUPER_HI(3, "SUPER_HI");

    private final int code;
    private final String name;

    SwipeDirectionEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public static SwipeDirectionEnum fromProtoOrdinal(int ordinal) {
        // Proto: SWIPE_UNSPECIFIED=0, LEFT=1, RIGHT=2
        return switch (ordinal) {
            case 1 -> LEFT;
            case 2 -> RIGHT;
            default -> throw new IllegalArgumentException("Invalid swipe direction: " + ordinal);
        };
    }
}
