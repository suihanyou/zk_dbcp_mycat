package net.snailgame.db.config;

/**
 * <p>
 * Title: EnumDbType.java
 * </p>
 * <p>
 * Description:
 * </p>
 * <p>
 * Copyright: Copyright (c) 2017
 * </p>
 * 
 * @author SHY 2017年11月8日
 * @version 1.0
 */
public enum EnumDbType {
    DBCP("DBCP"), DRUID("DRUID");
    private String type;

    private EnumDbType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public static EnumDbType getEnumDbType(String type) {
        for (EnumDbType c : EnumDbType.values()) {
            if (c.getType().equals(type)) {
                return c;
            }
        }
        return null;
    }
}
