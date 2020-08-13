package com.imooc.miaosha.util;

import java.util.UUID;

public class UUIDUtil {

	public static String simUuid() {
		return UUID.randomUUID().toString();
	}

	public static String uuid() {
		return UUID.randomUUID().toString().replace("-", "");
	}
}
