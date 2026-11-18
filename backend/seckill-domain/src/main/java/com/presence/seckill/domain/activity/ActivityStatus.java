package com.presence.seckill.domain.activity;

/** 活动状态：0 草稿 / 1 已上架 / 2 已下架 */
public final class ActivityStatus {
    public static final int DRAFT = 0;
    public static final int ONLINE = 1;
    public static final int OFFLINE = 2;

    private ActivityStatus() {
    }
}
