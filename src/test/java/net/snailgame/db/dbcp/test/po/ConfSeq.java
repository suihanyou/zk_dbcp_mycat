package net.snailgame.db.dbcp.test.po;

/**
 * <p>
 * Title: ConfSeq.java<��p>
 * <p>
 * Description: <��p>
 * <p>
 * Copyright: Copyright (c) 2015<��p>
 * 
 * @author SHY 2015��10��19��
 * @version 1.0
 */

public class ConfSeq {
    private String seq_name;
    private long seq_value;
    private int seq_step;
    private long seq_next;

    public String getSeq_name() {
        return seq_name;
    }

    public void setSeq_name(String seq_name) {
        this.seq_name = seq_name;
    }

    public long getSeq_value() {
        return seq_value;
    }

    public void setSeq_value(long seq_value) {
        this.seq_value = seq_value;
    }

    public int getSeq_step() {
        return seq_step;
    }

    public void setSeq_step(int seq_step) {
        this.seq_step = seq_step;
    }

    public long getSeq_next() {
        return seq_next;
    }

    public void setSeq_next(long seq_next) {
        this.seq_next = seq_next;
    }

}
