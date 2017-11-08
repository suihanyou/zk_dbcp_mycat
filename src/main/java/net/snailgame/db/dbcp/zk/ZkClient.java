package net.snailgame.db.dbcp.zk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import net.snailgame.db.config.ZkDbConfig;
import net.snailgame.db.dbcp.vo.ConnMycatInfoVo;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.CloseableUtils;
import org.apache.curator.utils.ZKPaths;
import org.apache.curator.utils.ZKPaths.PathAndNode;
import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Perms;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.springframework.beans.BeanUtils;

/**
 * <p>
 * Title: ZkClient.java
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
public class ZkClient {
    private final static Logger logger = Logger.getLogger(ZkClient.class);
    private CuratorFramework zkConn = null;
    private String lockNode = null;
    private InterProcessMutex lock;
    private final MycatNodeService mycatNodeService = new MycatNodeService();
    private String rootPath;
    private long lastConnTime = 0; // 上次重连时间
    private ZkDbConfig zkDbConfig;

    public ZkClient(BasicDataSource dataSourceTemplate, ZkDbConfig zkDbConfig) throws Exception {
        if (zkConn == null) {
            this.zkDbConfig = zkDbConfig;
            this.rootPath = ZKPaths.PATH_SEPARATOR + zkDbConfig.getMycatCluster();
            this.lockNode = rootPath + zkDbConfig.getLockNode();

            // 建立zk连接
            zkConn = buildConnection(zkDbConfig.getZkUrl(), zkDbConfig.getZkNamespace(), zkDbConfig.getId0(),
                    zkDbConfig.getId1());
            this.lock = new InterProcessMutex(zkConn, getLockNode());

            String servicePath = rootPath + zkDbConfig.getServiceNode();
            String clientPath = rootPath + zkDbConfig.getClientNode();
            // 启动服务端监控
            PathChildrenCache pathChildrenCache = getpathChildrenCache(this, servicePath, true);
            pathChildrenCache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
            // 启动客户端监控
            TreeCache treeCache = getTreeCache(zkDbConfig.getZkUrl(), zkDbConfig.getZkNamespace(), zkDbConfig.getId0(),
                    zkDbConfig.getId1(), getMycatNodeService(), this, clientPath);
            treeCache.start();

            // 初始化节点管理类
            mycatNodeService.init(dataSourceTemplate, servicePath, clientPath);

            doReconnMycat(true);

            // 启动监控线程
            MonitorThread monitorThread = new MonitorThread(this);
            monitorThread.setDaemon(true);
            monitorThread.start();
        }
    }

    // flag为ture抛弃connNow，重新连接，flag 为false判断是否需要重连
    public void doReconnMycat(boolean flag) throws Exception {
        try {
            // 重连的时候一个一个判断，防止潮汐乱迁移
            tryLock();
            // 从zk上初始化节点管理类的数据
            ConnMycatInfoVo connNow = flag ? null : mycatNodeService.getConnNow();
            initNodeInfo(connNow);

            // 执行重连
            doReConnect();
        } finally {
            unLock();
        }
    }

    private void initNodeInfo(ConnMycatInfoVo connNow) throws Exception {
        Stat stat = new Stat();
        // 从zk上初始化mycat节点信息
        List<String> nodes = getChildren(mycatNodeService.getServicePath());
        if (nodes == null || nodes.size() == 0) {
            throw new RuntimeException("节点：" + zkDbConfig.getServiceNode() + "没有找到已注册的数据库服务");
        }

        for (String node : nodes) {
            // 针对每个服务端查看有多少个client连接
            String clientTempPath = ZKPaths.makePath(mycatNodeService.getClientPath(), node);
            getDataAndStat(clientTempPath, stat);
            String serviceTempPath = ZKPaths.makePath(mycatNodeService.getServicePath(), node);
            getMycatNodeService().addMycatNode(serviceTempPath, getDate(serviceTempPath), stat);
        }

        if (!getMycatNodeService().setConnMycatInfo(connNow)) {
            throw new RuntimeException("初始化mycat注册信息失败");
        }
    }

    private class MonitorThread extends Thread {
        private ZkClient zkClient;

        public MonitorThread(ZkClient zkClient) {
            this.zkClient = zkClient;
        }

        @Override
        public void run() {
            while (true) {
                if (zkClient.getMycatNodeService().isNeedReconn()) {
                    // 如果需要重连，当前连接为空，立即重连
                    if (zkClient.getMycatNodeService().getConnNow() == null) {
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
                zkClient.doReconnMycat(false);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private long checkReconn(long lastConnTime, long skipTime) {
            return System.currentTimeMillis() - lastConnTime - skipTime;
        }
    }


    // 重连
    private void doReConnect() throws Exception {
        if (mycatNodeService.isNeedReconn()) {

            ConnMycatInfoVo connNow = mycatNodeService.getConnNow();
            ConnMycatInfoVo connNext = mycatNodeService.getConnNext();

            BasicDataSource dataSource = new BasicDataSource();
            BeanUtils.copyProperties(mycatNodeService.getDataSource(), dataSource, "logWriter", "loginTimeout");
            buildDbUrl(dataSource, connNext.getUrl());
            dataSource.setPassword(connNext.getPasswd());
            dataSource.setUsername(connNext.getUserName());
            if (mycatNodeService.getDataSource() != null) {
                mycatNodeService.closeDb();
            }
            mycatNodeService.setDataSource(dataSource);

            // 注册到选好的mycat 服务下
            try {
                createNode(ZKPaths.makePath(connNext.getClientPath(), connNext.getNodeId()), CreateMode.EPHEMERAL);
                // 如果是切换节点，删除之前注册的节点
                if (connNow != null) {
                    deleteNode(ZKPaths.makePath(connNow.getClientPath(), connNow.getNodeId()));
                }
            } catch (Exception e) {
            }

            mycatNodeService.reset(false);
            lastConnTime = System.currentTimeMillis(); // 重连完成记录上次重连时间


            logger.debug("SnailZookeeperDataSource::doReConnect::end");
        }
    }

    private void buildDbUrl(BasicDataSource dataSource, String addr) {
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


    private List<String> getChildren(String path) throws Exception {
        return zkConn.getChildren().forPath(path);
    }

    private byte[] getDataAndStat(String path, Stat stat) throws Exception {
        return zkConn.getData().storingStatIn(stat).forPath(path);
    }

    private byte[] getDate(String path) throws Exception {
        return zkConn.getData().forPath(path);
    }

    private void createNode(String path, CreateMode createMode) throws Exception {
        zkConn.create().withMode(createMode).forPath(path);
    }

    public void deleteNode(String path) throws Exception {
        if (zkConn.checkExists().forPath(path) != null)
            zkConn.delete().inBackground().forPath(path);
    }

    // 尝试获取分布式锁
    private void tryLock() throws Exception {
        mycatNodeService.getLock().lock();
        lock.acquire();
    }

    private void unLock() {
        try {
            // 释放
            if (lock.isAcquiredInThisProcess())
                lock.release();
            mycatNodeService.getLock().unlock();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static CuratorFramework buildConnection(String url, String namespace, final String id0, final String id1) {
        ACLProvider aclProvider = new ACLProvider() {
            private List<ACL> acl;

            @Override
            public List<ACL> getDefaultAcl() {
                if (acl == null) {
                    ArrayList<ACL> acl = ZooDefs.Ids.CREATOR_ALL_ACL;
                    acl.clear();
                    acl.add(new ACL(Perms.ALL, new Id("auth", id0 + ":" + id1)));
                    this.acl = acl;
                }
                return acl;
            }

            @Override
            public List<ACL> getAclForPath(String path) {
                return acl;
            }
        };
        CuratorFramework curatorFramework = CuratorFrameworkFactory.builder().aclProvider(aclProvider)
                .connectString(url).authorization("digest", (id0 + ":" + id1).getBytes())
                .retryPolicy(new ExponentialBackoffRetry(100, 6)).build();

        curatorFramework.start();
        try {
            curatorFramework.blockUntilConnected(3, TimeUnit.SECONDS);
            if (curatorFramework.getZookeeperClient().isConnected()) {
                return curatorFramework.usingNamespace(namespace);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // fail situation
        curatorFramework.close();
        throw new RuntimeException("failed to connect to zookeeper service : " + url);
    }

    // 用户client端节点的监控
    private static TreeCache getTreeCache(final String url, final String namespace, final String id0, final String id1,
            final MycatNodeService mycatNodeService, final ZkClient zkClient, final String clientPath) throws Exception {
        final TreeCache cached = new TreeCache(zkClient.getZkConn(), clientPath);
        final int pathDeep = clientPath.split(ZKPaths.PATH_SEPARATOR).length;
        cached.getListenable().addListener(new TreeCacheListener() {
            @Override
            public void childEvent(CuratorFramework client, TreeCacheEvent event) throws Exception {
                TreeCacheEvent.Type eventType = event.getType();
                switch (eventType) {
                    case CONNECTION_RECONNECTED:
                        break;
                    case CONNECTION_SUSPENDED:
                        break;
                    case CONNECTION_LOST:
                        logger.error("Connection error,waiting...");
                        CloseableUtils.closeQuietly(client);
                        client = buildConnection(url, namespace, id0, id1);
                        // 客户端节点判断重新注册
                        String clientRegist = ZKPaths.makePath(mycatNodeService.getConnNow().getClientPath(),
                                mycatNodeService.getConnNow().getNodeId());
                        if (client.checkExists().forPath(clientRegist) == null) {
                            client.create().withMode(CreateMode.EPHEMERAL).forPath(clientRegist);
                        }
                        break;
                    case NODE_ADDED:
                        int changePathDeep = event.getData().getPath().split(ZKPaths.PATH_SEPARATOR).length;
                        if (changePathDeep - pathDeep == 2) {
                            // 客户端注册，不需要判断是否需要重连，因为客户端启动的时候已经判断过了
                            PathAndNode pathAndNode = ZKPaths.getPathAndNode(event.getData().getPath());
                            // 获取对应的客户端数
                            List<String> childsList = client.getChildren().forPath(pathAndNode.getPath());
                            pathAndNode = ZKPaths.getPathAndNode(pathAndNode.getPath());
                            mycatNodeService.setCliNode(pathAndNode.getNode(),
                                    childsList == null ? 0 : childsList.size());
                        }
                        break;
                    case NODE_UPDATED:
                        logger.debug(event);
                        break;
                    case NODE_REMOVED:
                        int removePathDeep = event.getData().getPath().split(ZKPaths.PATH_SEPARATOR).length;
                        if (removePathDeep - pathDeep == 2) {
                            zkClient.doReconnMycat(false);
                        }
                        break;
                    default:
                        break;
                }
            }
        });
        return cached;
    }

    // 用户服务端节点的监控
    public PathChildrenCache getpathChildrenCache(final ZkClient zkClient, String path, Boolean cacheData)
            throws Exception {
        final PathChildrenCache cached = new PathChildrenCache(zkClient.getZkConn(), path, cacheData);
        cached.getListenable().addListener(new PathChildrenCacheListener() {
            @Override
            public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
                PathChildrenCacheEvent.Type eventType = event.getType();
                switch (eventType) {
                    case CONNECTION_RECONNECTED:
                        cached.rebuild();
                        break;
                    case CONNECTION_SUSPENDED:
                        break;
                    case CONNECTION_LOST:
                        System.out.println("Connection error,waiting...");
                        break;
                    case CHILD_ADDED:
                        zkClient.doReconnMycat(false);
                        break;
                    case CHILD_UPDATED:
                        logger.debug(event);
                        break;
                    case CHILD_REMOVED:
                        // 服务端节点丢失，不需要判断，全节点都掉了
                        mycatNodeService.removeMycatNode(event.getData().getPath());
                        break;
                    default:
                        logger.debug("PathChildrenCache changed : {path:" + event.getData().getPath() + " data:"
                                + new String(event.getData().getData()) + "}");
                }
            }
        });
        return cached;
    }


    public static void main(String[] args) {
        String a = "/a/b/c";
        System.out.print(a.split("/").length);
    }

    public MycatNodeService getMycatNodeService() {
        return mycatNodeService;
    }

    public String getLockNode() {
        return lockNode;
    }

    public CuratorFramework getZkConn() {
        return zkConn;
    }
}
