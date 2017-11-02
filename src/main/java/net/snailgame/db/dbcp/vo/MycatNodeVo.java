package net.snailgame.db.dbcp.vo;

import java.util.Map;

/**
 * <p>
 * Title: MycatNodeVO.java
 * </p>
 * <p>
 * Description:
 * </p>
 * <p>
 * Copyright: Copyright (c) 2017
 * </p>
 * 
 * @author SHY 2017年10月30日
 * @version 1.0
 */
public class MycatNodeVo {
    private String url;
    private int weight;
    private int number;
    private float rate;
    private Map<String, MycatNodeUser> users;

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
        this.rate = (float) this.number / this.weight;
    }

    public synchronized void lessNumber() {
        this.number--;
        this.rate = (float) this.number / this.weight;
    }

    public Map<String, MycatNodeUser> getUsers() {
        return users;
    }

    public void setUsers(Map<String, MycatNodeUser> users) {
        this.users = users;
    }

    public float getRate() {
        return rate;
    }

    public void setRate(float rate) {
        this.rate = rate;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
