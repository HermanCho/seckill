package com.imooc.miaosha.exception;

import com.imooc.miaosha.result.CodeMsg;

// RuntimeException , unchecked异常，才默认被事务回滚
public class GlobalException extends RuntimeException{

	private static final long serialVersionUID = 1L;
	
	private CodeMsg cm;
	
	public GlobalException(CodeMsg cm) {
		super(cm.toString());
		this.cm = cm;
	}

	public CodeMsg getCm() {
		return cm;
	}

}
