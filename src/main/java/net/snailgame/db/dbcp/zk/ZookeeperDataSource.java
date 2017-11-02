package net.snailgame.db.dbcp.zk;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

import net.snailgame.db.config.ZkDbConfig;
import net.snailgame.db.dbcp.vo.ConnMycatInfoVo;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.log4j.Logger;
import org.apache.zookeeper.data.Stat;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;

import org.springframework.beans.BeanUtils;

/**
 * <p>
 * Title: SnailZookeeperDataSource.java<／p>
 * <p>
 * Description: 通过zookeeper获取数据库连接信息并连接<／p>
 * <p>
 * Copyright: Copyright (c) 2016<／p>
 * 
 * @author Shy
 * @date 2016年1月11日
 * @version 1.0
 * 
 */

public class ZookeeperDataSource implements DataSource {
    private static final Logger logger = Logger.getLogger(ZookeeperDataSource.class);

    private ZkDbConfig zkDbConfig;
    private volatile DataSource dataSource;
    private BasicDataSource dataSourceTemplate;
    private MycatNodeService mycatNodeService;
    private ZkClient zkClient;

    private long lastConnTime = 0; // 上次重连时间

    @PostConstruct
    public synchronized void init() throws Exception {
        zkClient = new ZkClient(zkDbConfig);
        setMycatNodeService(zkClient.getMycatNodeService());
        try {
            zkClient.tryLock();
            // 从zk上初始化mycat节点信息
            List<String> nodes = zkClient.getChildren(zkDbConfig.getRegistNode());
            if (nodes == null || nodes.size() == 0) {
                throw new RuntimeException("节点：" + zkDbConfig.getRegistNode() + "没有找到已注册的数据库服务");
            }
            Stat stat = new Stat();
            for (String node : nodes) {
                getMycatNodeService().addClientNode(node, zkClient.getDataAndStat(node, stat), stat);
            }

            // 初始化mycat节点服务
            getMycatNodeService().init(zkDbConfig.getDbName());
            if (!getMycatNodeService().setConnMycatInfo(null)) {
                throw new RuntimeException("初始化mycat注册信息失败");
            }
            doReConnect(mycatNodeService);
        } finally {
            zkClient.unLock();
        }

        new MonitorThread(this).start();
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void setDataSourceTemplate(BasicDataSource template) {
        this.dataSourceTemplate = template;
    }

    private static long checkReconn(long lastConnTime, long skipTime) {
        return System.currentTimeMillis() - lastConnTime - skipTime;
    }

    public class MonitorThread extends Thread {
        private ZookeeperDataSource snailZookeeperDataSource;

        public MonitorThread(ZookeeperDataSource snailZookeeperDataSource) {
            this.snailZookeeperDataSource = snailZookeeperDataSource;
        }

        @Override
        public void run() {
            while (true) {
                if (snailZookeeperDataSource.getMycatNodeService().isNeedReconn()
                        && checkReconn(lastConnTime, zkDbConfig.getReConnectSkipTime()) > 0) {
                    try {
                        snailZookeeperDataSource.doReConnect(snailZookeeperDataSource.getMycatNodeService());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                try {
                    sleep(zkDbConfig.getCheckSkipTime());
                } catch (InterruptedException e) {
                    logger.error(e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    // 重连
    private void doReConnect(MycatNodeService mycatNodeService) throws Exception {
        // 重连的时候一个一个判断，防止潮汐乱迁移
        ConnMycatInfoVo connNow = mycatNodeService.getConnNow();
        ConnMycatInfoVo connNext = mycatNodeService.getConnNext();
        logger.info("SnailZookeeperDataSource::doReConnect::nodeNow=[" + connNow == null ? "null" : connNow.getUrl()
                + "]" + "::nodeNex=[" + connNext == null ? "null" : connNow.getUrl() + "]");
        BasicDataSource dataSource = new BasicDataSource();
        BeanUtils.copyProperties(dataSourceTemplate, dataSource, "logWriter", "loginTimeout");
        setDbUrl(dataSource, connNext.getUrl());
        dataSource.setPassword(connNext.getPasswd());
        dataSource.setUsername(connNext.getUserName());
        if (this.dataSource != null) {
            ((BasicDataSource) this.dataSource).close();
        }
        this.dataSource = dataSource;
        mycatNodeService.reset();

        lastConnTime = System.currentTimeMillis(); // 重连完成记录上次重连时间

        logger.debug("SnailZookeeperDataSource::doReConnect::end");
    }

    public void setDbUrl(BasicDataSource dataSource, String addr) {
        String prefix = null;
        if (dataSource.getDriverClassName().equals("com.mysql.jdbc.Driver")) {
            prefix = "jdbc:mysql://";
        }
        if (dataSource.getDriverClassName().equals("oracle.jdbc.driver.OracleDriver")) {
            prefix = "jdbc:oracle:thin:";
        }
        logger.debug("setDbUrl:" + addr);
        dataSource.setUrl(prefix + addr + zkDbConfig.getPostfix());
    }

    public String getDbUrl(BasicDataSource dataSource, String addr) {
        String prefix = null;
        if (dataSource.getDriverClassName().equals("com.mysql.jdbc.Driver")) {
            prefix = "jdbc:mysql://";
        }
        if (dataSource.getDriverClassName().equals("oracle.jdbc.driver.OracleDriver")) {
            prefix = "jdbc:oracle:thin:";
        }
        logger.debug("getDbUrl:" + addr);
        StringBuilder builder = new StringBuilder();
        builder.append(prefix);
        builder.append(addr);
        builder.append("/");
        builder.append(getMycatNodeService().getDbName());
        builder.append(zkDbConfig.getPostfix());

        return builder.toString();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return dataSource.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        dataSource.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        dataSource.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return dataSource.getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return dataSource.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return dataSource.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return dataSource.isWrapperFor(iface);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = null;
        try {
            zkClient.tryLock();
            connection = dataSource.getConnection();
        } catch (Exception e) {
            logger.error(e.getMessage());
            try {
                doReConnect(this.getMycatNodeService());
            } catch (Exception e1) {
                e1.printStackTrace();
                logger.error(e1.getMessage());
            }
            return this.dataSource.getConnection();
        } finally {
            zkClient.unLock();
        }

        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new UnsupportedOperationException("Not supported by SnailZookeeperDataSource");
    }

    public ZkDbConfig getZkDbConfig() {
        return zkDbConfig;
    }

    public void setZkDbConfig(ZkDbConfig zkDbConfig) {
        this.zkDbConfig = zkDbConfig;
    }

    public MycatNodeService getMycatNodeService() {
        return mycatNodeService;
    }

    public void setMycatNodeService(MycatNodeService mycatNodeService) {
        this.mycatNodeService = mycatNodeService;
    }

}
