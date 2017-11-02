package net.snailgame.db.dbcp.test.dao.impl;

import java.util.List;

import javax.annotation.Resource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.support.SqlSessionDaoSupport;

import net.snailgame.db.dbcp.test.dao.ConfSeqDao;
import net.snailgame.db.dbcp.test.po.ConfSeq;

/**
 * <p>
 * Title: ConfSeqDaoImpl.java
 * <p>
 * Description:
 * <p>
 * Copyright: Copyright (c) 2015
 * 
 * @author SHY 2015年10月19日
 * @version 1.0
 */

public class ConfSeqDaoImpl extends SqlSessionDaoSupport implements ConfSeqDao {
    @Resource
    public void setSqlSessionFactory(SqlSessionFactory sqlSessionFactory) {
        super.setSqlSessionFactory(sqlSessionFactory);
    }

    @Override
    public ConfSeq getConfSeqByName(String seqName) {
        return getSqlSession().selectOne("net.snailgame.inter.po.ConfSeq.getSeqMapByName", seqName);
    }

    @Override
    public int insert(ConfSeq confSeq) {
        return getSqlSession().insert("net.snailgame.inter.po.ConfSeq.insert", confSeq);
    }

    @Override
    public int updateStepUp(ConfSeq confSeq) {
        return getSqlSession().update("net.snailgame.inter.po.ConfSeq.updateStepUp", confSeq);
    }

    @Override
    public int updateStepUpByName(String seqName) {
        return getSqlSession().update("net.snailgame.inter.po.ConfSeq.updateStepUpByName", seqName);
    }

    @Override
    public List<ConfSeq> getAllConfSeqs() {
        return getSqlSession().selectList("net.snailgame.inter.po.ConfSeq.getAllConfSeqs");
    }

    @Override
    public int updateAllConfStepUp() {
        return getSqlSession().update("net.snailgame.inter.po.ConfSeq.updateAllConfStepUp");
    }
}
