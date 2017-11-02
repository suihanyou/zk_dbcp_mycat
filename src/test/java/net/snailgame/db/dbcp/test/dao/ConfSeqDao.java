package net.snailgame.db.dbcp.test.dao;

import java.util.List;

import net.snailgame.db.dbcp.test.po.ConfSeq;



/**
 * <p>
 * Title: ConfSeqDao.java
 * <p>
 * Description:
 * <p>
 * Copyright: Copyright (c) 2015
 * 
 * @author SHY 2015年10月19日
 * @version 1.0
 */


public interface ConfSeqDao {

    public ConfSeq getConfSeqByName(String seqName);

    public int insert(ConfSeq confSeq);

    public int updateStepUp(ConfSeq confSeq);

    public List<ConfSeq> getAllConfSeqs();

    public int updateAllConfStepUp();

    public int updateStepUpByName(String seqName);
}
