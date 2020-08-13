package com.imooc.miaosha.redis.PrefixKey;

public class MiaoshaKey extends BasePrefix{

	private MiaoshaKey( int expireSeconds, String prefix) {
		super(expireSeconds, prefix);
	}
	public static MiaoshaKey isGoodsOver = new MiaoshaKey(0, "go");
	public static MiaoshaKey getMiaoshaPath = new MiaoshaKey(60, "mp");
	public static MiaoshaKey getMiaoshaVerifyCode = new MiaoshaKey(300, "vc");


	public static MiaoshaKey robRedisStock = new MiaoshaKey(300, "rrs");


	public static MiaoshaKey getMiaoshaMessage = new MiaoshaKey(0, "me");
	public static MiaoshaKey getResendCount = new MiaoshaKey(0, "rsc");

	public static MiaoshaKey isConsumed = new MiaoshaKey(0, "con");



}
