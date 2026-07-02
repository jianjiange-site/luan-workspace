package com.dating.match.constant;

import lombok.Getter;

/**
 * Match source: the entry action that created the match.
 * Independent of BH/DH; check user.user_type separately.
 */
@Getter
public enum MatchSourceEnum {
    SWIPE_MATCH("SWIPE_MATCH"),
    SWIPE_SUPER_HI("SWIPE_SUPER_HI");

    private final String value;

    MatchSourceEnum(String value) {
        this.value = value;
    }
}
