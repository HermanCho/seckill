package com.imooc.miaosha.rabbitmq;

import com.imooc.miaosha.redis.PrefixKey.MiaoshaKey;
import com.imooc.miaosha.redis.RedisService;
import com.imooc.miaosha.util.UUIDUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MQSender {

    private static Logger log = LoggerFactory.getLogger(MQSender.class);


    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RedisService redisService;



    public void sendMiaoshaMessage(MiaoshaMessage mm) {
        String msg = RedisService.beanToString(mm);
        CorrelationData correlationData = gencorrelationData();
//        System.out.println("发送消息，分配ID：" + correlationData.getId());
        // 放到redis里面，将 id 与 msg 一一对应
        redisService.set(MiaoshaKey.getMiaoshaMessage, correlationData.getId(), msg);
        rabbitTemplate.convertAndSend(MQConfig.MIAOSHA_EXCHANGE, MQConfig.MIAOSHA_ROUTING_KEY, msg, correlationData);
//        rabbitTemplate.convertAndSend("noexist", MQConfig.MIAOSHA_ROUTING_KEY, msg, correlationData);

    }

    /***
     * @Description: 重发，此时不用、也不能设置/更新 correalationData
     * @Author: hermanCho
     * @Date: 2020-08-09
     * @Param mm:
     * @return: void
     **/

    public void reSendMiaoshaMessage(MiaoshaMessage mm, CorrelationData correlationData) {
//        System.out.println("重发消息");
        String msg = RedisService.beanToString(mm);
        rabbitTemplate.convertAndSend(MQConfig.MIAOSHA_EXCHANGE, MQConfig.MIAOSHA_ROUTING_KEY, msg, correlationData);
//        rabbitTemplate.convertAndSend("noexist", MQConfig.MIAOSHA_ROUTING_KEY, msg, correlationData);
    }


    private CorrelationData gencorrelationData() {
        CorrelationData correlationData = new CorrelationData();
        String uuid = UUIDUtil.simUuid();
        correlationData.setId(uuid);
        return correlationData;
    }



//    public void sendStrMessage(String msg) {
////        CorrelationData correlationData = gencorrelationData();
////        System.out.println("分配ID：" + correlationData.getId());
////        System.out.println("发送消息");
////        rabbitTemplate.convertAndSend(MQConfig.MIAOSHA_EXCHANGE, MQConfig.MIAOSHA_ROUTING_KEY, msg, correlationData);
//////        rabbitTemplate.convertAndSend("noexist", MQConfig.MIAOSHA_ROUTING_KEY, msg, correlationData);
////    }
////



}
