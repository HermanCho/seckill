package com.imooc.miaosha.redis.PrefixKey;

public class LockKey extends BasePrefix {

    private LockKey(int expireSeconds, String prefix) {
        super(expireSeconds, prefix);
    }

    public static LockKey Lock = new LockKey(5, "lock");
}

