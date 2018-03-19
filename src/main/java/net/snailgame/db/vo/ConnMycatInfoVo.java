package net.snailgame.db.vo;

import java.util.UUID;

/**
 * <p>
 * Title: ConnMycatInfo.java
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
public class ConnMycatInfoVo {
    private String servicePath;
    private String clientPath;
    private String url;
    private String userName;
    private String passwd;
    private String nodeId;

    public ConnMycatInfoVo(String servicePath, String clientPath, MycatNodeVo vo, String userName, String appName) {
        this.url = vo.getUrl();
        this.servicePath = servicePath;
        this.clientPath = clientPath;
        this.passwd = vo.getUsers().get(userName).getPasswd();
        this.userName = vo.getUsers().get(userName).getUserName();
        this.nodeId = appName.concat(UUID.randomUUID().toString());
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPasswd() {
        return passwd;
    }

    public void setPasswd(String passwd) {
        this.passwd = passwd;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getServicePath() {
        return servicePath;
    }

    public void setServicePath(String servicePath) {
        this.servicePath = servicePath;
    }

    public String getClientPath() {
        return clientPath;
    }

    public void setClientPath(String clientPath) {
        this.clientPath = clientPath;
    }

    public String getNodeId() {
        return nodeId;
    }
}
