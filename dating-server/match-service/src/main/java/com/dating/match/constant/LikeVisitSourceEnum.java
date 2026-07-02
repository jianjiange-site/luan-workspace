package com.dating.match.constant;

import lombok.Getter;

/**
 * Source of like/visit records.
 * For like: SWIPE_RIGHT(1), DH_PLAN_ONLINE(2), DH_PLAN_OFFLINE(3)
 * For visit: PROFILE_VIEW(1), DH_PLAN_ONLINE(2), DH_PLAN_OFFLINE(3)
 */
@Getter
public enum LikeVisitSourceEnum {
    SWIPE_RIGHT(1),
    PROFILE_VIEW(1),
    DH_PLAN_ONLINE(2),
    DH_PLAN_OFFLINE(3);

    private final int code;

    LikeVisitSourceEnum(int code) {
        this.code = code;
    }
}
