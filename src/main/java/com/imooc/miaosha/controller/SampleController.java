package com.imooc.miaosha.controller;

import com.imooc.miaosha.domain.MiaoshaUser;
import com.imooc.miaosha.domain.User;
import com.imooc.miaosha.rabbitmq.MQSender;
import com.imooc.miaosha.rabbitmq.MiaoshaMessage;
import com.imooc.miaosha.redis.PrefixKey.AccessKey;
import com.imooc.miaosha.redis.PrefixKey.UserKey;
import com.imooc.miaosha.redis.RedisService;
import com.imooc.miaosha.result.Result;
import com.imooc.miaosha.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/***
  * @Description:  测试用，无关
  * @Author: hermanCho
  * @Date: 2020-08-13
  * @Param null:
  * @return: null
  **/

@Slf4j
@Controller
@RequestMapping("/demo")
public class SampleController {

    @Autowired
    UserService userService;

    @Autowired
    RedisService redisService;

    @Autowired
    MQSender sender;

    @RequestMapping("/mq")
    @ResponseBody
    public Result<String> mq() {
        MiaoshaMessage mm = new MiaoshaMessage();
        mm.setGoodsId(1);
        MiaoshaUser miaoshaUser = new MiaoshaUser();
        miaoshaUser.setId(11111111111L);
        mm.setUser(miaoshaUser);
//        sender.sendMiaoshaMessage("example");
        sender.sendMiaoshaMessage(mm);
        return Result.success("Hello，world");
    }

    @RequestMapping("/mq2")
    @ResponseBody
    public Result<String> mq2() {
//        MiaoshaMessage mm = new MiaoshaMessage();
//        mm.setGoodsId(122);
//        MiaoshaUser miaoshaUser = new MiaoshaUser();
//        miaoshaUser.setId(3L);
//        mm.setUser(miaoshaUser);
        sender.sendStrMessage("aa");
        return Result.success("Hello，world");
    }

    @RequestMapping("/hello")
    @ResponseBody
    public Result<String> home() {

        AccessKey ak = AccessKey.withExpire(0);
        String key = "a";
        redisService.set(ak, key, 1);


        Integer countOld = redisService.getSet(ak, key, 2, Integer.class);


        System.out.println(countOld);
        Integer count = redisService.get(ak, key, Integer.class);
        System.out.println(count);


        return Result.success("Hello，world");
    }

//    @RequestMapping("/error")
//    @ResponseBody
//    public Result<String> error() {
//        return Result.error(CodeMsg.SESSION_ERROR);
//    }

    @RequestMapping("/hello/themaleaf")
    public String themaleaf(Model model) {
        model.addAttribute("name", "Joshua");
        return "hello";
    }

    @RequestMapping("/db/get")
    @ResponseBody
    public Result<User> dbGet() {
        User user = userService.getById(1);
        return Result.success(user);
    }


    @RequestMapping("/db/tx")
    @ResponseBody
    public Result<Boolean> dbTx() {
        userService.tx();
        return Result.success(true);
    }

    @RequestMapping("/redis/get")
    @ResponseBody
    public Result<User> redisGet() {
        User user = redisService.get(UserKey.getById, "" + 1, User.class);
        return Result.success(user);
    }

    @RequestMapping("/redis/set")
    @ResponseBody
    public Result<Boolean> redisSet() {
        User user = new User();
        user.setId(1);
        user.setName("1111");
        redisService.set(UserKey.getById, "" + 1, user);//UserKey:id1
        return Result.success(true);
    }


}
