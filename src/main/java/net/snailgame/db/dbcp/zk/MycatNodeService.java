package net.snailgame.db.dbcp.zk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.data.Stat;

import net.snailgame.db.util.FastJSONUtils;
import net.snailgame.db.dbcp.vo.ConnMycatInfoVo;
import net.snailgame.db.dbcp.vo.MycatNodeVo;

/**
 * <p>
 * Title: MycatNodeService.java
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
public class MycatNodeService {
    private static Map<String, MycatNodeVo> nodes = new HashMap<String, MycatNodeVo>(); // 当前注册的节点集合
    private static Set<String> badNodes = new HashSet<String>(); // 坏节点集合
    private String userName;
    private ConnMycatInfoVo connNow;
    private ConnMycatInfoVo connNext;
    private boolean needReconn = false; // 是否需要重连
    private ReentrantLock lock = new ReentrantLock();
    // private String servicePath;
    private String clientPath;

    public void init(String userName, String servicePath, String clientPath) {
        this.userName = userName;
        // this.servicePath = servicePath;
        this.clientPath = clientPath;
    }

    public void reset() {
        try {
            lock.lock();
            setNeedReconn(false);
            setConnNow(connNext);
            setConnNext(null);
        } finally {
            lock.unlock();
        }
    }

    public boolean setConnMycatInfo(ConnMycatInfoVo connNow) {
        try {
            lock.lock();
            String serviceTemp = null;
            String clientTemp = null;
            float rate = 100.0f;
            if (connNow != null) {
                serviceTemp = connNow.getServicePath();
                clientTemp = connNow.getClientPath();
            }

            for (String key : nodes.keySet()) {
                // 跳过坏了的节点
                if (getBadNodes().contains(key)) {
                    continue;
                }
                // 大于两个节点的差值才有比较换节点
                if (rate > (nodes.get(key).getRate() + (float) 2 / nodes.get(key).getWeight())) {
                    serviceTemp = key;

                    clientTemp = ZKPaths.makePath(clientPath, ZKPaths.getNodeFromPath(key));
                    rate = nodes.get(key).getRate();
                }
            }
            // 找到了下个需要连接的节点
            if (serviceTemp != null) {
                connNext = new ConnMycatInfoVo(serviceTemp, clientTemp, nodes.get(serviceTemp), getUserName());
                if (connNow == null) {
                    setNeedReconn(true);
                } else if (!connNext.getServicePath().equals(connNow.getServicePath())) {
                    // 如果下个需要连接的节点不是当前节点
                    setNeedReconn(true);
                } else {
                    // 没有换节点，不需要重连
                    connNext = null;
                    setNeedReconn(false);
                }
                return true;
            } else {
                // 查找数据库失败
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    public void removeService(String path) {
        try {
            lock.lock();
            nodes.remove(path);
            getBadNodes().remove(path);
            if (path.equals(getConnNow().getServicePath()))
                setConnMycatInfo(null);
        } finally {
            lock.unlock();
        }
    }

    public void removeClient(String path) {
        try {
            lock.lock();
            nodes.get(path).lessNumber();
        } finally {
            lock.unlock();
        }
    }

    public void addServiceNode(String path, byte[] nodeInfo, Stat stat) {
        try {
            lock.lock();
            MycatNodeVo mycatNodeVO = FastJSONUtils.toBeanFromByteArray(nodeInfo, MycatNodeVo.class);
            if (!mycatNodeVO.getUsers().containsKey(getUserName())) {
                throw new RuntimeException("节点：" + path + "未能获取数据库：" + getUserName() + "的配置，请检查datebaseName");
            }
            mycatNodeVO.setNumber(stat.getNumChildren()); // 客户端的节点个数
            nodes.put(path, mycatNodeVO);

            if (getBadNodes().contains(path)) {
                getBadNodes().remove(path);
            }
        } finally {
            lock.unlock();
        }
    }

    public void addClientNode(String path, byte[] nodeInfo, Stat stat) {
        try {
            lock.lock();
            if (nodes.get(path) == null) {
                addServiceNode(path, nodeInfo, stat);
            } else {
                nodes.get(path).setNumber(stat.getNumChildren());
            }
        } finally {
            lock.unlock();
        }
    }

    public ConnMycatInfoVo getConnNow() {
        return connNow;
    }

    public boolean isNeedReconn() {
        return needReconn;
    }

    private void setNeedReconn(boolean needReconn) {
        this.needReconn = needReconn;
    }

    public ConnMycatInfoVo getConnNext() {
        return connNext;
    }

    private void setConnNext(ConnMycatInfoVo connNext) {
        this.connNext = connNext;
    }

    private Set<String> getBadNodes() {
        return badNodes;
    }

    public void addBadNodeNow() {
        badNodes.add(connNow.getServicePath());
    }

    private void setConnNow(ConnMycatInfoVo connNow) {
        this.connNow = connNow;
    }

    private String getUserName() {
        return userName;
    }

}
