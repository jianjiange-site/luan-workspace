package com.dating.match.constant;

import lombok.Getter;

/**
 * Target user type: BH (biological human) or DH (digital human).
 */
@Getter
public enum TargetUserTypeEnum {
    BH(1),
    DH(2);

    private final int code;

    TargetUserTypeEnum(int code) {
        this.code = code;
    }

    public static TargetUserTypeEnum fromCode(int code) {
        return switch (code) {
            case 1 -> BH;
            case 2 -> DH;
            default -> throw new IllegalArgumentException("Invalid target user type: " + code);
        };
    }
}
