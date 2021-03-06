package net.snailgame.db.zk;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.curator.utils.ZKPaths;
import org.apache.log4j.Logger;
import org.apache.zookeeper.data.Stat;

import com.alibaba.druid.pool.DruidDataSource;

import net.snailgame.db.util.FastJSONUtils;
import net.snailgame.db.vo.ConnMycatInfoVo;
import net.snailgame.db.vo.MycatNodeVo;
import net.snailgame.db.config.EnumDbType;

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
    private final static Logger logger = Logger.getLogger(MycatNodeService.class);
    private static Map<String, MycatNodeVo> nodes = new HashMap<String, MycatNodeVo>(); // 当前注册的节点集合
    private static Set<String> badNodes = new HashSet<String>(); // 坏节点集合
    private String userName;
    private ConnMycatInfoVo connNow;
    private ConnMycatInfoVo connNext;
    private String lastNodeId; // 上一个连接的id
    private boolean needReconn = false; // 是否需要重连
    private final ReentrantLock lock = new ReentrantLock();
    private String servicePath;
    private String clientPath;
    private volatile DataSource dataSource;
    private String appName;
    private boolean connFlag = false;

    public void init(DataSource dataSource, String userName, String servicePath, String clientPath, String appName) {
        this.userName = userName;
        this.dataSource = dataSource;
        this.servicePath = servicePath;
        this.clientPath = clientPath;
        this.appName = appName;
    }

    public void reset(boolean reconn) {
        try {
            lock.lock();
            if (connNow != null) {
                try {
                    nodes.get(connNow.getServicePath()).lessNumber();
                } catch (Exception e) {

                }
            }
            if (connNext != null)
                try {
                    nodes.get(connNext.getServicePath()).addNumber();
                } catch (Exception e) {

                }
            this.lastNodeId = connNow == null ? null : connNow.getNodeId();
            setNeedReconn(reconn);
            setConnNow(connNext);
            setConnNext(null);
            logger.warn("节点重连到:" + connNow.getUrl());
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
            float rateDiff = 1f;
            boolean flag = false;
            if (connNow != null) {
                serviceTemp = connNow.getServicePath();
                clientTemp = connNow.getClientPath();
                rateDiff = 2f;
                rate = nodes.get(connNow.getServicePath()).getRate();
            }

            for (String key : nodes.keySet()) {
                // 跳过坏了的节点
                if (getBadNodes().contains(key)) {
                    continue;
                }
                // 大于两个节点的差值才有比较换节点
                if (rate >= (nodes.get(key).getRate() + rateDiff / nodes.get(key).getWeight())) {
                    serviceTemp = key;

                    clientTemp = ZKPaths.makePath(getClientPath(), ZKPaths.getNodeFromPath(key));
                    rate = nodes.get(key).getRate();
                    flag = true;
                }
            }
            // 找到了下个需要连接的节点
            if (flag)
                if (serviceTemp != null) {
                    connNext = new ConnMycatInfoVo(serviceTemp, clientTemp, nodes.get(serviceTemp), getUserName(),
                            appName);
                    if (connNow == null) {
                        setNeedReconn(true);
                        return true;
                    }
                    if (connNext.getServicePath().equals(connNow.getServicePath())) {
                        connNext = null;
                        setNeedReconn(false);
                    } else {
                        setNeedReconn(true);
                    }
                    return true;
                } else {
                    // 查找数据库失败
                    return false;
                }
            else {
                if (connNow == null) {
                    return false;
                }
                return true;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param path:传入变化的mycat 的注册节点的路径 /mycat/mycat-clust-1/regist/X-X---X
     */
    public void removeMycatNode(String path) {
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

    /**
     * @param path:传入变化的节点名称
     */
    public void removeCliNode(String nodeName) {
        try {
            lock.lock();
            nodes.get(ZKPaths.makePath(this.getServicePath(), nodeName)).lessNumber();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param path:传入变化的mycat 的注册节点的路径 /mycat/mycat-clust-1/regist/X---X---X
     * @param nodeInfo:path对应的getDate
     * @param stat:/mycat/mycat-clust-1/client/X--X-X 对应的getChildren 的stat
     */
    public void addMycatNode(String path, byte[] nodeInfo, Stat stat) {
        try {
            lock.lock();
            MycatNodeVo mycatNodeVO = FastJSONUtils.toBeanFromByteArray(nodeInfo, MycatNodeVo.class);
            if (!mycatNodeVO.getUsers().containsKey(getUserName())) {
                throw new RuntimeException("节点：" + path + "未能获取数据库用户：" + getUserName() + "的配置，请检查userName");
            }
            mycatNodeVO.setNumber(stat == null ? 0 : stat.getNumChildren()); // 客户端的节点个数
            nodes.put(path, mycatNodeVO);

            if (getBadNodes().contains(path)) {
                getBadNodes().remove(path);
            }
            if (needReconn && connNext == null) {
                setConnMycatInfo(null);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param path:传入变化的节点名称
     */
    public void addCliNode(String nodeName) {
        try {
            lock.lock();
            nodes.get(ZKPaths.makePath(this.getServicePath(), nodeName)).addNumber();
        } finally {
            lock.unlock();
        }
    }

    public void setCliNode(String nodeName, int number) {
        try {
            lock.lock();
            String path = ZKPaths.makePath(this.getServicePath(), nodeName);
            // 判断下空，防止这个时候有新的mycat 注册上来，而信息还没有注册到内存的空指针错误
            if (nodes.get(path) != null) {
                nodes.get(path).setNumber(number);
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

    public ReentrantLock getLock() {
        return lock;
    }

    public String getLastNodeId() {
        return lastNodeId;
    }

    public DataSource getDataSource() {
        return dataSource;
    }


    public void closeDb(EnumDbType enumDbType) throws SQLException {
        switch (enumDbType) {
            case DBCP:
                ((BasicDataSource) this.getDataSource()).close();
                break;
            case DRUID:
                ((DruidDataSource) this.getDataSource()).close();
                break;

            default:
                break;
        }

    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public String getServicePath() {
        return servicePath;
    }

    public String getClientPath() {
        return clientPath;
    }

    public boolean isConnFlag() {
        return connFlag;
    }

    public void setConnFlag(boolean connFlag) {
        this.connFlag = connFlag;
    }

}
