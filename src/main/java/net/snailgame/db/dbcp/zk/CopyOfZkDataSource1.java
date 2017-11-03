package net.snailgame.db.dbcp.zk;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

import javax.sql.DataSource;

import net.snailgame.db.config.ZkDbConfig;
import net.snailgame.db.dbcp.vo.ConnMycatInfoVo;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.curator.utils.ZKPaths;
import org.apache.ibatis.transaction.Transaction;
import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * <p>
 * Title: ZkDataSource.java<／p>
 * <p>
 * Description: <／p>
 * <p>
 * Copyright: Copyright (c) 2017<／p>
 * 
 * @author Shy
 * @date 2017年11月2日
 * @version 1.0
 */
public class CopyOfZkDataSource1 implements DataSource, BeanFactoryPostProcessor {
    private static final Logger logger = Logger.getLogger(CopyOfZkDataSource1.class);

    private ZkDbConfig zkDbConfig;
    private volatile DataSource dataSource;
    private BasicDataSource dataSourceTemplate;
    private MycatNodeService mycatNodeService;
    private ZkClient zkClient;

    private long lastConnTime = 0; // 上次重连时间


    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        dataSourceTemplate = beanFactory.getBean(BasicDataSource.class);
        if (dataSourceTemplate == null) {
            throw new NoSuchBeanDefinitionException(BasicDataSource.class);
        }
        zkDbConfig = beanFactory.getBean(ZkDbConfig.class);
        if (zkDbConfig == null) {
            throw new NoSuchBeanDefinitionException(ZkDbConfig.class);
        }
        if (dataSourceTemplate.getUsername() == null) {
            throw new FatalBeanException("初始化数据库节点失败，数据库用户名为空");
        }
        try {
            init(dataSourceTemplate.getUsername());
        } catch (Exception e) {
            e.printStackTrace();
            throw new FatalBeanException("从zk上初始化mycat节点失败");
        }
        DataSourceTransactionManager transaction = beanFactory.getBean(DataSourceTransactionManager.class);
        beanFactory.destroyBean(dataSourceTemplate);
        transaction.setDataSource(this.dataSource);
        transaction.afterPropertiesSet();
    }

    public synchronized void init(String userName) throws Exception {
        zkClient = new ZkClient(userName, zkDbConfig);
        setMycatNodeService(zkClient.getMycatNodeService());
        try {
            Stat stat = new Stat();
            zkClient.tryLock();
            // 从zk上初始化mycat节点信息
            List<String> nodes = zkClient.getChildren(zkClient.getServicePath());
            if (nodes == null || nodes.size() == 0) {
                throw new RuntimeException("节点：" + zkDbConfig.getServiceNode() + "没有找到已注册的数据库服务");
            }

            for (String node : nodes) {
                String tempPath = ZKPaths.makePath(zkClient.getServicePath(), node);
                getMycatNodeService().addClientNode(tempPath, zkClient.getDataAndStat(tempPath, stat), stat);
            }

            if (!getMycatNodeService().setConnMycatInfo(null)) {
                throw new RuntimeException("初始化mycat注册信息失败");
            }
            doReConnect(mycatNodeService);
        } finally {
            zkClient.unLock();
        }

        MonitorThread monitorThread = new MonitorThread(this);
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static long checkReconn(long lastConnTime, long skipTime) {
        return System.currentTimeMillis() - lastConnTime - skipTime;
    }

    public class MonitorThread extends Thread {
        private CopyOfZkDataSource1 zkDataSource;

        public MonitorThread(CopyOfZkDataSource1 zkDataSource) {
            this.zkDataSource = zkDataSource;
        }

        @Override
        public void run() {
            while (true) {
                if (zkDataSource.getMycatNodeService().isNeedReconn()
                        && checkReconn(lastConnTime, zkDbConfig.getReConnectSkipTime()) > 0) {
                    try {
                        zkDataSource.doReConnect(zkDataSource.getMycatNodeService());
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
        if (mycatNodeService.isNeedReconn()) {
            // 重连的时候一个一个判断，防止潮汐乱迁移
            ConnMycatInfoVo connNow = mycatNodeService.getConnNow();
            ConnMycatInfoVo connNext = mycatNodeService.getConnNext();

            BasicDataSource dataSource = new BasicDataSource();
            BeanUtils.copyProperties(dataSourceTemplate, dataSource, "logWriter", "loginTimeout");
            setDbUrl(dataSource, connNext.getUrl());
            dataSource.setPassword(connNext.getPasswd());
            dataSource.setUsername(connNext.getUserName());
            if (this.dataSource != null) {
                ((BasicDataSource) this.dataSource).close();
            }

            this.dataSource = dataSource;

            /*try {
                // 注册到选好的mycat 服务下
                zkClient.createNode(connNext.getClientPath(), CreateMode.EPHEMERAL);
                if (connNow != null) {
                    zkClient.deleteNode(connNow.getClientPath());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }*/

            mycatNodeService.reset();
            lastConnTime = System.currentTimeMillis(); // 重连完成记录上次重连时间

            logger.debug("SnailZookeeperDataSource::doReConnect::end");
        }
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
                this.getMycatNodeService().addBadNodeNow();
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

    public void setZkDbConfig(ZkDbConfig zkDbConfig) throws Exception {
        this.zkDbConfig = zkDbConfig;
    }

    public MycatNodeService getMycatNodeService() {
        return mycatNodeService;
    }

    public void setMycatNodeService(MycatNodeService mycatNodeService) {
        this.mycatNodeService = mycatNodeService;
    }

}
