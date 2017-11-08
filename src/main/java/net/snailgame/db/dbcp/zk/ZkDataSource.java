package net.snailgame.db.dbcp.zk;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import javax.sql.DataSource;

import net.snailgame.db.config.EnumDbType;
import net.snailgame.db.config.ZkDbConfig;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.curator.utils.ZKPaths;
import org.apache.log4j.Logger;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.support.SqlSessionDaoSupport;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.alibaba.druid.pool.DruidDataSource;

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
    private MycatNodeService mycatNodeService;
    private SqlSessionFactoryBean sqlSessionFactoryBean;
    private ZkClient zkClient;

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
        zkDbConfig = beanFactory.getBean(ZkDbConfig.class);
        if (zkDbConfig == null) {
            throw new NoSuchBeanDefinitionException(ZkDbConfig.class);
        }
        DataSource dataSource = null;
        String userName = null;
        if (zkDbConfig.getDbType() == EnumDbType.DBCP) {
            BasicDataSource basicDataSource = beanFactory.getBean(BasicDataSource.class);
            if (basicDataSource == null) {
                throw new NoSuchBeanDefinitionException(BasicDataSource.class);
            }
            dataSource = basicDataSource;
            userName = basicDataSource.getUsername();
        } else if (zkDbConfig.getDbType() == EnumDbType.DRUID) {
            DruidDataSource druidDataSource = beanFactory.getBean(DruidDataSource.class);
            if (druidDataSource == null) {
                throw new NoSuchBeanDefinitionException(BasicDataSource.class);
            }
            druidDataSource.setFailFast(true);
            dataSource = druidDataSource;
            userName = druidDataSource.getUsername();
        }

        try {
            zkClient = new ZkClient(dataSource, zkDbConfig, userName, zkDbConfig.getDbType());
            setMycatNodeService(zkClient.getMycatNodeService());
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

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return mycatNodeService.getDataSource().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        mycatNodeService.getDataSource().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        mycatNodeService.getDataSource().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return mycatNodeService.getDataSource().getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return mycatNodeService.getDataSource().getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return mycatNodeService.getDataSource().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return mycatNodeService.getDataSource().isWrapperFor(iface);
    }

    public String getUrl() {
        return ((BasicDataSource) mycatNodeService.getDataSource()).getUrl();
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = null;
        try {
            connection = mycatNodeService.getDataSource().getConnection();
        } catch (Exception e) {
            logger.error(this.getUrl());
            e.printStackTrace();
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
                zkClient.doReconnMycat(true);
            } catch (Exception e1) {
                e1.printStackTrace();
                logger.error(e1.getMessage());
                throw new SQLException("getConnection failed!!!" + e.getMessage());
            }
            if (getMycatNodeService().getDataSource() != null)
                return getMycatNodeService().getDataSource().getConnection();
            else {
                return null;
            }
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
