package net.snailgame.db.config;

import net.snailgame.db.util.AesCode;

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
    private String lockNode = "/lockNode";
    private String serviceNode;
    private String clientNode;
    private String zkUrl;
    private String zkNamespace = "mycat";
    private String id0;
    private String id1;
    private String postfix;
    private long reConnectSkipTime = 30 * 60 * 1000; // 重连的间隔时间
    private long checkSkipTime = 5 * 60 * 1000;// 检查间隔时间
    private String mycatCluster;
    private EnumDbType dbType;

    public String getLockNode() {
        return lockNode;
    }

    public String getId0() {
        return id0;
    }

    public void setId0(String id0) {
        this.id0 = AesCode.cbcDecode(id0);
    }

    public String getId1() {
        return id1;
    }

    public void setId1(String id1) {
        this.id1 = AesCode.cbcDecode(id1);
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

    public String getZkUrl() {
        return zkUrl;
    }

    public void setZkUrl(String zkUrl) {
        this.zkUrl = zkUrl;
    }

    public String getZkNamespace() {
        return zkNamespace;
    }

    public long getCheckSkipTime() {
        return checkSkipTime;
    }

    public void setCheckSkipTime(long checkSkipTime) {
        this.checkSkipTime = checkSkipTime;
    }

    public String getMycatCluster() {
        return mycatCluster;
    }

    public void setMycatCluster(String mycatCluster) {
        this.mycatCluster = mycatCluster;
    }

    public String getServiceNode() {
        return serviceNode;
    }

    public void setServiceNode(String serviceNode) {
        this.serviceNode = serviceNode;
    }

    public String getClientNode() {
        return clientNode;
    }

    public void setClientNode(String clientNode) {
        this.clientNode = clientNode;
    }

    public EnumDbType getDbType() {
        return dbType;
    }

    public void setDbType(String dbType) {
        if ((this.dbType = EnumDbType.getEnumDbType(dbType.toUpperCase())) == null) {
            throw new RuntimeException("错误的数据库类型，目前只支持dbcp和druid");
        }
    }
}
