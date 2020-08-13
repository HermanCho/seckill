package com.imooc.miaosha.service;

import com.imooc.miaosha.dao.ErrorMsgDao;
import com.imooc.miaosha.rabbitmq.ErrorMsg;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ErrorMsgService {
    @Autowired
    ErrorMsgDao errorMsgDao;

    public void insert(ErrorMsg errorMsg){
        errorMsgDao.insert(errorMsg);
    }

    public void resetErrorMsg(){
        errorMsgDao.reset();
    }

}
