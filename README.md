## 项目说明

本项目是基于 SpringBoot+ Spring MVC +Mybatis+Redis+RabbitMQ 高并发秒杀类系统。

项目参照自慕课网上的高并发秒杀系统系统，在完全理解原项目的基础上，主要增加实现了：

- **RabbitMQ的可靠性传输：包括生产者的可靠性投递、MQ Broker的消息持久化、消费者的成功消费** 
- **RabbitMQ消息的幂等性**
- **Redis库存的补充，解决Redis预减库存导致的问题**
- 让同一用户只有一个请求可以生效

其它的还有许多小改动，比如`springboot`版本升级、`log4j2`的整合等。

相关笔记及重点请参考本人博客：[https://blog.csdn.net/Unknownfuture/article/details/108020496](https://blog.csdn.net/Unknownfuture/article/details/108020496)

(踩坑记录、相关技术原理等都在博客)

------------

## 项目技术点

1. RabbitMQ的可靠性传输及消息的幂等性
2. 使用Redis，解决**分布式Session问题**
3. 使用Redis**预减库存**，拦截大部分无效流量，减少MySQL数据库的压力
4. 利用**内存标记**，减少对Redis的访问，减轻Redis的压力
5. 使用消息队列`RabbitMQ`完成异步下单，提升用户体验，削峰和降流。
6. 安全性优化：双重md5密码校验，秒杀接口地址的隐藏，接口限流防刷。

-------------

## 开发工具

IntelliJ IDEA 2018.1.8 x64

## 开发环境

| JDK  | Maven | Mysql | SpringBoot    | redis | RabbitMQ | Erlang |
| ---- | ----- | ----- | ------------- | ----- | -------- | ------ |
| 1.8  | 3.3.9 | 8.0   | 2.1.9.RELEASE | 5.0.8 | 3.8.5    | 23.0.2 |



## 使用说明

1. 安装redis、mysql、rabbitmq、maven等环境

2. 运行sql文件

3. 启动前，检查配置 `application.properties` 中相关redis、mysql、rabbitmq地址

4. 在数据库秒杀商品表里面设置合理的秒杀开始时间与结束时间

5. 登录地址：http://localhost:8080/login/to_login

6. 压测相关，具体看 `com.imooc.miaosha.util.UserUtil`类

   

   



