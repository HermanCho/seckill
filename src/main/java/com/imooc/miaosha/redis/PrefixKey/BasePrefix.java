package com.imooc.miaosha.redis.PrefixKey;

public abstract class BasePrefix implements KeyPrefix{

	/***
	  * @Description: 单位:秒s ，0代表永不过期
	  * @Author: hermanCho
	  * @Date: 2020-08-15
	  * @Param null:
	  * @return: null
	  **/
	private int expireSeconds;
	
	private String prefix;
	
	public BasePrefix(String prefix) {//0代表永不过期
		this(0, prefix);
	}
	
	public BasePrefix( int expireSeconds, String prefix) {
		this.expireSeconds = expireSeconds;
		this.prefix = prefix;
	}
	
	public int expireSeconds() {//默认0代表永不过期
		return expireSeconds;
	}

	public String getPrefix() {
		String className = getClass().getSimpleName();
		return className+":" + prefix;
	}

	public String getSimPrefix(){
		return prefix;
	}

}
