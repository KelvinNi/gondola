/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the New BSD License.
 * See the accompanying LICENSE file for terms.
 */

package com.yahoo.gondola.container.client;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The type Zookeeper stat.
 */
public class ZookeeperStat {

    /**
     * Current mode of the member.
     */
    public enum Mode {
        NORMAL, SLAVE, MIGRATING_1, MIGRATING_2
    }

    /**
     * Status of the mode.
     */
    public enum Status {
        STOP, RUNNING, FAILED, SYNCED, APPROACHED
    }

    public int memberId;
    public String shardId;
    public Mode mode = Mode.NORMAL;
    public Status status = Status.STOP;
    public String reason = null;

    @Override
    public String toString() {
        return "ZookeeperStat{" + "memberId=" + memberId + ", shardId='" + shardId + '\'' + ", mode=" + mode
               + ", status=" + status + ", reason='" + reason + '\'' + '}';
    }

    @JsonIgnore
    public boolean isSlaveOperational() {
        return mode == Mode.SLAVE && status != Status.FAILED;
    }

    @JsonIgnore
    public boolean isNormalOperational() {
        return mode == Mode.NORMAL && status == Status.RUNNING;
    }

    @JsonIgnore
    public boolean isMigrating1Operational() {
        return mode == Mode.MIGRATING_1 && status == Status.RUNNING;
    }

    @JsonIgnore
    public boolean isMigrating2Operational() {
        return mode == Mode.MIGRATING_2 && status == Status.RUNNING;
    }
}
