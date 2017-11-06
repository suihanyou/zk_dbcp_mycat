package net.snailgame.db.dbcp.zk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import net.snailgame.db.config.ZkDbConfig;

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
    private String servicePath;
    private String clientPath;


    public ZkClient(String userName, ZkDbConfig zkDbConfig) throws Exception {
        if (zkConn == null) {
            zkConn = buildConnection(zkDbConfig.getZkUrl(), zkDbConfig.getZkNamespace(), zkDbConfig.getId0(),
                    zkDbConfig.getId1());
            rootPath = ZKPaths.PATH_SEPARATOR + zkDbConfig.getMycatCluster();
            this.lockNode = rootPath + zkDbConfig.getLockNode();
            this.servicePath = rootPath + zkDbConfig.getServiceNode();
            this.clientPath = rootPath + zkDbConfig.getClientNode();
            lock = new InterProcessMutex(zkConn, getLockNode());
            // 启动服务端监控
            PathChildrenCache pathChildrenCache = getpathChildrenCache(zkConn, getServicePath(), true);
            pathChildrenCache.start();
            // 启动客户端监控
            TreeCache treeCache = getTreeCache(zkDbConfig.getZkUrl(), zkDbConfig.getZkNamespace(), zkDbConfig.getId0(),
                    zkDbConfig.getId1(), getMycatNodeService(), zkConn, getClientPath());
            treeCache.start();

            mycatNodeService.init(userName, this.servicePath, this.clientPath);
        }
    }

    public List<String> getChildren(String path) throws Exception {
        return zkConn.getChildren().forPath(path);
    }

    public List<String> getChildrenAndStat(String path, Stat stat) throws Exception {
        return zkConn.getChildren().storingStatIn(stat).forPath(path);
    }

    public byte[] getDataAndStat(String path, Stat stat) throws Exception {
        return zkConn.getData().storingStatIn(stat).forPath(path);
    }

    public byte[] getDate(String path) throws Exception {
        return zkConn.getData().forPath(path);
    }

    public void createNode(String path, CreateMode createMode) throws Exception {
        zkConn.create().withMode(createMode).forPath(path);
    }

    public void deleteNode(String path) throws Exception {
        if (zkConn.checkExists().forPath(path) != null)
            zkConn.delete().inBackground().forPath(path);
    }

    public void updateNodeDate(String path, String data) throws Exception {
        zkConn.setData().forPath(path, data.getBytes());
    }

    // 尝试获取分布式锁
    public void tryLock() throws Exception {
        lock.acquire();
    }

    public void unLock() {
        try {
            // 释放
            if (lock.isAcquiredInThisProcess())
                lock.release();
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
            final MycatNodeService mycatNodeService, CuratorFramework client, final String clientPath) throws Exception {
        final TreeCache cached = new TreeCache(client, clientPath);
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
                            // 客户端停止
                            PathAndNode pathAndNode = ZKPaths.getPathAndNode(event.getData().getPath());
                            // 获取对应的客户端数
                            List<String> childsList = client.getChildren().forPath(pathAndNode.getPath());
                            mycatNodeService.setCliNode(pathAndNode.getNode(),
                                    childsList == null ? 0 : childsList.size());
                            // 触发判断是否需要重连
                            mycatNodeService.setConnMycatInfo(mycatNodeService.getConnNow());
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
    public PathChildrenCache getpathChildrenCache(CuratorFramework client, String path, Boolean cacheData)
            throws Exception {
        final PathChildrenCache cached = new PathChildrenCache(client, path, cacheData);
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
                        // 服务端节点增加
                        mycatNodeService.addMycatNode(event.getData().getPath(), event.getData().getData(), null);
                        // 触发判断是否需要重连
                        mycatNodeService.setConnMycatInfo(mycatNodeService.getConnNow());
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

    public String getClientPath() {
        return clientPath;
    }

    public String getServicePath() {
        return servicePath;
    }
}
