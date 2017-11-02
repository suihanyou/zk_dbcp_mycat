package net.snailgame.db.dbcp.zk;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    private Map<String, MycatNodeVo> nodes; // 当前注册的节点集合
    private Set<String> badNodes; // 坏节点集合
    private String dbName;
    private ConnMycatInfoVo connNow;
    private ConnMycatInfoVo connNext;
    private boolean needReconn = false; // 是否需要重连

    public void init(String dbName) {
        this.dbName = dbName;
        this.badNodes = new HashSet<String>();
    }

    public void reset() {
        setNeedReconn(false);
        setConnNow(connNext);
        setConnNext(null);
    }

    public boolean setConnMycatInfo(ConnMycatInfoVo connNow) {
        String temp = null;
        float rate = 100.0f;
        if (connNow != null) {
            temp = connNow.getPath();
        }

        for (String key : nodes.keySet()) {
            // 跳过坏了的节点
            if (getBadNodes().contains(key)) {
                continue;
            }
            // 大于两个节点的差值才有比较换节点
            if (rate > (nodes.get(key).getRate() + (float) 2 / nodes.get(key).getWeight())) {
                temp = key;
                rate = nodes.get(key).getRate();
            }
        }
        // 找到了下个需要连接的节点
        if (temp != null) {
            connNext = new ConnMycatInfoVo(temp, nodes.get(temp), getDbName());
            if (connNow == null) {
                setNeedReconn(true);
            } else if (!connNext.getPath().equals(connNow.getPath())) {
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
    }

    public void removeService(String path) {
        nodes.remove(path);
        getBadNodes().remove(path);
        if (path.equals(getConnNow().getPath()))
            setConnMycatInfo(null);
    }

    public void removeClient(String path) {
        nodes.get(path).lessNumber();
    }

    public void addServiceNode(String path, byte[] nodeInfo, Stat stat) {
        MycatNodeVo mycatNodeVO = FastJSONUtils.toBeanFromByteArray(nodeInfo, MycatNodeVo.class);
        if (!mycatNodeVO.getUsers().containsKey(getDbName())) {
            throw new RuntimeException("节点：" + path + "未能获取数据库：" + getDbName() + "的配置，请检查datebaseName");
        }
        mycatNodeVO.setNumber(stat.getNumChildren()); // 客户端的节点个数
        nodes.put(path, mycatNodeVO);

        if (getBadNodes().contains(path)) {
            getBadNodes().remove(path);
        }
    }

    public void addClientNode(String path, byte[] nodeInfo, Stat stat) {
        if (nodes.get(path) == null) {
            addServiceNode(path, nodeInfo, stat);
        } else {
            nodes.get(path).setNumber(stat.getNumChildren());
        }
    }

    public void addMycatNode(String path, byte[] nodeInfo, int number) {
        MycatNodeVo mycatNodeVO = FastJSONUtils.toBeanFromByteArray(nodeInfo, MycatNodeVo.class);
        if (!mycatNodeVO.getUsers().containsKey(getDbName())) {
            throw new RuntimeException("节点：" + path + "未能获取数据库：" + getDbName() + "的配置，请检查datebaseName");
        }
        mycatNodeVO.setNumber(number); // 客户端的节点个数
        nodes.put(path, mycatNodeVO);

        if (getBadNodes().contains(path)) {
            getBadNodes().remove(path);
        }
    }

    public String getDbName() {
        return dbName;
    }

    public ConnMycatInfoVo getConnNow() {
        return connNow;
    }

    public boolean isNeedReconn() {
        return needReconn;
    }

    public void setNeedReconn(boolean needReconn) {
        this.needReconn = needReconn;
    }

    public ConnMycatInfoVo getConnNext() {
        return connNext;
    }

    public void setConnNext(ConnMycatInfoVo connNext) {
        this.connNext = connNext;
    }

    public Set<String> getBadNodes() {
        return badNodes;
    }

    public void addBadNodes(String badNode) {
        this.badNodes.add(badNode);
    }

    public void setConnNow(ConnMycatInfoVo connNow) {
        this.connNow = connNow;
    }

}
