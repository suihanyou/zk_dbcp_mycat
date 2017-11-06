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
import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.support.SqlSessionDaoSupport;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
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
public class ZkDataSource implements DataSource, BeanFactoryPostProcessor, BeanPostProcessor {
    private static final Logger logger = Logger.getLogger(ZkDataSource.class);

    private ZkDbConfig zkDbConfig;
    private volatile DataSource dataSource;
    private MycatNodeService mycatNodeService;
    private SqlSessionFactoryBean sqlSessionFactoryBean;
    private ZkClient zkClient;
    private long lastConnTime = 0; // 上次重连时间


    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof SqlSessionDaoSupport) {
            SqlSessionDaoSupport sessionDaoSupport = (SqlSessionDaoSupport) bean;
            try {
                sessionDaoSupport.setSqlSessionFactory(sqlSessionFactoryBean.getObject());
            } catch (Exception e) {
                e.printStackTrace();
                throw new FatalBeanException("初始化sessionDaoSupport 失败");
            }
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }


    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        BasicDataSource dataSourceTemplate = beanFactory.getBean(BasicDataSource.class);
        if (dataSourceTemplate == null) {
            throw new NoSuchBeanDefinitionException(BasicDataSource.class);
        }
        zkDbConfig = beanFactory.getBean(ZkDbConfig.class);
        if (zkDbConfig == null) {
            throw new NoSuchBeanDefinitionException(ZkDbConfig.class);
        }
        this.dataSource = dataSourceTemplate;
        try {
            init(dataSourceTemplate.getUsername());
        } catch (Exception e) {
            e.printStackTrace();
            throw new FatalBeanException("从zk上初始化mycat节点失败");
        }

        try {
            DataSourceTransactionManager transactionManager = beanFactory.getBean(DataSourceTransactionManager.class);
            transactionManager.setDataSource(this);
            transactionManager.afterPropertiesSet();

            sqlSessionFactoryBean = beanFactory.getBean(SqlSessionFactoryBean.class);
            sqlSessionFactoryBean.setDataSource(this);
            sqlSessionFactoryBean.afterPropertiesSet();

        } catch (Exception e) {
            e.printStackTrace();
            throw new FatalBeanException("从zk上初始化mycat节点失败");
        }
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
                // 针对每个服务端查看有多少个client连接
                String clientTempPath = ZKPaths.makePath(zkClient.getClientPath(), node);
                zkClient.getDataAndStat(clientTempPath, stat);
                String serviceTempPath = ZKPaths.makePath(zkClient.getServicePath(), node);
                getMycatNodeService().addMycatNode(serviceTempPath, zkClient.getDate(serviceTempPath), stat);
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
        private ZkDataSource zkDataSource;

        public MonitorThread(ZkDataSource zkDataSource) {
            this.zkDataSource = zkDataSource;
        }

        @Override
        public void run() {
            while (true) {
                if (zkDataSource.getMycatNodeService().isNeedReconn()) {
                    // 如果需要重连，当前连接为空，立即重连
                    if (zkDataSource.getMycatNodeService().getConnNow() == null) {
                        doReconn();
                    } else {
                        // 如果需要重连，当前连接不为空，到时间后再重连
                        if (checkReconn(lastConnTime, zkDbConfig.getReConnectSkipTime()) > 0) {
                            doReconn();
                        }
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

        private void doReconn() {
            try {
                zkDataSource.doReConnect(zkDataSource.getMycatNodeService());
            } catch (Exception e) {
                e.printStackTrace();
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
            BeanUtils.copyProperties(this.dataSource, dataSource, "logWriter", "loginTimeout");
            setDbUrl(dataSource, connNext.getUrl());
            dataSource.setPassword(connNext.getPasswd());
            dataSource.setUsername(connNext.getUserName());
            if (this.dataSource != null) {
                ((BasicDataSource) this.dataSource).close();
            }

            this.dataSource = dataSource;

            // 注册到选好的mycat 服务下
            try {
                zkClient.createNode(ZKPaths.makePath(connNext.getClientPath(), connNext.getNodeId()),
                        CreateMode.EPHEMERAL_SEQUENTIAL);
                // 如果是切换节点，删除之前注册的节点
                if (connNow != null) {
                    zkClient.deleteNode(ZKPaths.makePath(connNow.getClientPath(), connNow.getNodeId()));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }


            mycatNodeService.reset(false);
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
            connection = this.dataSource.getConnection();
        } catch (Exception e) {
            logger.error(e.getMessage());
            try {
                this.getMycatNodeService().addBadNodeNow();
                // 获取下一个节点失败，释放当前节点
                if (!getMycatNodeService().setConnMycatInfo(null)) {
                    if (getMycatNodeService().getConnNow() != null) {
                        zkClient.deleteNode(ZKPaths.makePath(getMycatNodeService().getConnNow().getClientPath(),
                                getMycatNodeService().getConnNow().getNodeId()));
                    }
                    getMycatNodeService().reset(true);
                    throw new RuntimeException("初始化mycat注册信息失败");
                }
                doReConnect(this.getMycatNodeService());
            } catch (Exception e1) {
                e1.printStackTrace();
                logger.error(e1.getMessage());
                throw new SQLException("getConnection failed!!!" + e.getMessage());
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
