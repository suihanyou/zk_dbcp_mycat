package net.snailgame.db.dbcp.test;

import java.util.List;

import net.snailgame.db.dbcp.test.dao.ConfSeqDao;
import net.snailgame.db.dbcp.test.po.ConfSeq;

import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.FileSystemXmlApplicationContext;

/**
 * <p>
 * Title: Test.java
 * </p>
 * <p>
 * Description:
 * </p>
 * <p>
 * Copyright: Copyright (c) 2017
 * </p>
 * 
 * @author SHY 2017年11月2日
 * @version 1.0
 */
public class Test1 {
    public ConfSeqDao confSeqDao;

    @Before
    public void init() {
        try {
            @SuppressWarnings("unused")
            ApplicationContext context = null;
            if (System.getProperty("file.separator").equals("\\")) {
                context = new FileSystemXmlApplicationContext("src/test/resources/applicationContext-base-db1.xml");
            } else {
                context = new FileSystemXmlApplicationContext("src/test/resources/applicationContext-base-db1.xml");
            }
            confSeqDao = context.getBean(ConfSeqDao.class);

            System.out.println("**********************************启动成功!!!");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("启动失败，抛除异常" + e.getMessage());
            System.exit(-1);
        }
    }

    @Test
    public void test() {
        while (true) {
            List<ConfSeq> list = confSeqDao.getAllConfSeqs();
            System.out.println(list.size());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
