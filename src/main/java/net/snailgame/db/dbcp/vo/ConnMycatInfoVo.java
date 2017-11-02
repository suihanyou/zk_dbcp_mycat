package net.snailgame.db.dbcp.vo;

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
    private String path;
    private String url;
    private String userName;
    private String passwd;

    public ConnMycatInfoVo(String path, MycatNodeVo vo, String dbName) {
        this.url = vo.getUrl();
        this.path = path;
        this.passwd = vo.getUsers().get(dbName).getPasswd();
        this.userName = vo.getUsers().get(dbName).getUserName();
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

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
