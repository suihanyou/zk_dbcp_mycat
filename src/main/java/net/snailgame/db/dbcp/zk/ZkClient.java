package net.snailgame.db.dbcp.zk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import net.snailgame.db.config.ZkDbConfig;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.CloseableUtils;
import org.apache.curator.utils.ZKPaths;
import org.apache.curator.utils.ZKPaths.PathAndNode;
import org.apache.log4j.Logger;
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
    private CountDownLatch countdown = new CountDownLatch(1);
    private final MycatNodeService mycatNodeService = new MycatNodeService();


    public ZkClient(ZkDbConfig zkDbConfig) throws Exception {
        if (zkConn == null) {
            zkConn = buildConnection(zkDbConfig.getZkUrl(), zkDbConfig.getZkNamespace(), zkDbConfig.getId0(),
                    zkDbConfig.getId1());
            this.lockNode = zkDbConfig.getLockNode();
            lock = new InterProcessMutex(zkConn, lockNode);

            TreeCache cache = getTreeCache(zkDbConfig.getZkUrl(), zkDbConfig.getZkNamespace(), zkDbConfig.getId0(),
                    zkDbConfig.getId1(), getMycatNodeService(), zkConn, zkDbConfig.getRegistNode());
            cache.start();
        }
    }

    public List<String> getChildren(String path) throws Exception {
        return zkConn.getChildren().forPath(path);
    }

    public byte[] getDataAndStat(String path, Stat stat) throws Exception {
        return zkConn.getData().storingStatIn(stat).forPath(path);
    }

    public byte[] getDate(String path) throws Exception {
        return zkConn.getData().forPath(path);
    }

    public void updateNodeDate(String path, String data) throws Exception {
        zkConn.setData().forPath(path, data.getBytes());
    }

    // 尝试获取分布式锁
    public void tryLock() throws Exception {
        countdown.await();
        lock.acquire();
    }

    public void unLock() {
        try {
            // 释放
            lock.release();
            countdown.countDown();
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

    private static TreeCache getTreeCache(final String url, final String namespace, final String id0, final String id1,
            final MycatNodeService mycatNodeService, CuratorFramework client, final String path) throws Exception {
        final TreeCache cached = new TreeCache(client, path);
        final int pathDeep = path.split(ZKPaths.PATH_SEPARATOR).length;
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
                        break;
                    case NODE_ADDED:
                        int changePathDeep = event.getData().getPath().split(ZKPaths.PATH_SEPARATOR).length;
                        // 服务端节点增加
                        if (changePathDeep - pathDeep == 1) {
                            mycatNodeService.addServiceNode(event.getData().getPath(), event.getData().getData(), event
                                    .getData().getStat());
                            // 触发判断是否需要重连
                            mycatNodeService.setConnMycatInfo(mycatNodeService.getConnNow());
                        } else if (changePathDeep - pathDeep == 2) {
                            // 客户端注册，不需要判断是否需要重连，因为客户端启动的时候已经判断过了
                            PathAndNode pathAndNode = ZKPaths.getPathAndNode(event.getData().getPath());
                            mycatNodeService.addClientNode(pathAndNode.getNode(), event.getData().getData(), event
                                    .getData().getStat());
                        }
                        break;
                    case NODE_UPDATED:
                        // 节点更新后对比下信息，或者重新初始化节点信息？
                        break;
                    case NODE_REMOVED:
                        int removePathDeep = event.getData().getPath().split(ZKPaths.PATH_SEPARATOR).length;
                        // 服务端节点丢失，不需要判断，全节点都掉了
                        if (removePathDeep - pathDeep == 1) {
                            mycatNodeService.removeService(event.getData().getPath());
                        } else if (removePathDeep - pathDeep == 2) {
                            // 客户端停止
                            PathAndNode pathAndNode = ZKPaths.getPathAndNode(event.getData().getPath());
                            mycatNodeService.removeClient(pathAndNode.getNode());
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

    public static void main(String[] args) {
        String a = "/a/b/c";
        System.out.print(a.split("/").length);
    }

    public MycatNodeService getMycatNodeService() {
        return mycatNodeService;
    }
}
