package net.snailgame.db.config;

/**
 * <p>
 * Title: ZkDbConfig.java
 * </p>
 * <p>
 * Description:
 * </p>
 * <p>
 * Copyright: Copyright (c) 2017
 * </p>
 * 
 * @author SHY 2017年10月31日
 * @version 1.0
 */
public class ZkDbConfig {
    private String lockNode;
    private String zkUrl;
    private String dbName;
    private String zkNamespace;
    private String id0;
    private String id1;
    private String registNode;
    private String postfix;
    private long reConnectSkipTime = 30 * 60 * 1000; // 重连的间隔时间
    private long checkSkipTime = 5 * 60 * 1000;// 检查间隔时间

    public String getLockNode() {
        return lockNode;
    }

    public void setLockNode(String lockNode) {
        this.lockNode = lockNode;
    }

    public String getId0() {
        return id0;
    }

    public void setId0(String id0) {
        this.id0 = id0;
    }

    public String getId1() {
        return id1;
    }

    public void setId1(String id1) {
        this.id1 = id1;
    }

    public String getRegistNode() {
        return registNode;
    }

    public void setRegistNode(String registNode) {
        this.registNode = registNode;
    }

    public String getPostfix() {
        return postfix;
    }

    public void setPostfix(String postfix) {
        this.postfix = postfix;
    }

    public long getReConnectSkipTime() {
        return reConnectSkipTime;
    }

    public void setReConnectSkipTime(long reConnectSkipTime) {
        this.reConnectSkipTime = reConnectSkipTime;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getZkUrl() {
        return zkUrl;
    }

    public void setZkUrl(String zkUrl) {
        this.zkUrl = zkUrl;
    }

    public String getZkNamespace() {
        return zkNamespace;
    }

    public void setZkNamespace(String zkNamespace) {
        this.zkNamespace = zkNamespace;
    }

    public long getCheckSkipTime() {
        return checkSkipTime;
    }

    public void setCheckSkipTime(long checkSkipTime) {
        this.checkSkipTime = checkSkipTime;
    }
}
