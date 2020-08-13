package com.imooc.miaosha.dao;

import com.imooc.miaosha.rabbitmq.ErrorMsg;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;


@Mapper
public interface ErrorMsgDao {
    @Insert("INSERT INTO error_msg(correlationId,type,errorCause,msg) VALUES(#{correlationId},#{type},#{errorCause},#{msg})")
    public int insert(ErrorMsg errorMsg);

    @Insert("delete from error_msg")
    public int reset();

}
