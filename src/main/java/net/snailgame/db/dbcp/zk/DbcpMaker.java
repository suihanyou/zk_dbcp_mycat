package net.snailgame.db.dbcp.zk;

import javax.sql.DataSource;

import org.apache.commons.dbcp.BasicDataSource;

/**
 * <p>
 * Title: DbcpMaker.java<／p>
 * <p>
 * Description: <／p>
 * <p>
 * Copyright: Copyright (c) 2017<／p>
 * 
 * @author Shy
 * @date 2017年11月3日
 * @version 1.0
 */
public class DbcpMaker {


    public static BasicDataSource initBasicDataSource() {
        BasicDataSource dbSource = new BasicDataSource();
        dbSource.setDriverClassName("com.mysql.jdbc.Driver");
        dbSource.setUsername("ocs");
        dbSource.setPassword("ocs");
        dbSource.setUrl("jdbc:mysql://192.168.110.3:8066/ocs?autoReconnect=true&failOverReadOnly=false&maxReconnects=5&useUnicode=true&characterEncoding=UTF-8");
        
        return dbSource;
    }
}
