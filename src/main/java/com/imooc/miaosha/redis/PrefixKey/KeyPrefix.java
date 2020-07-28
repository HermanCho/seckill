package com.imooc.miaosha.redis.PrefixKey;

public interface KeyPrefix {
		
	public int expireSeconds();
	
	public String getPrefix();
	
}
