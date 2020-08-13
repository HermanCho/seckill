package com.imooc.miaosha.rabbitmq;

import com.imooc.miaosha.redis.PrefixKey.KeyPrefix;
import com.imooc.miaosha.redis.PrefixKey.MiaoshaKey;
import com.imooc.miaosha.redis.RedisService;
import com.imooc.miaosha.service.ErrorMsgService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
/***
  * @Description:  定时任务，用于重发没有收到ack的消息
  * @Author: hermanCho
  * @Date: 2020-08-13
  **/

@Slf4j
//@EnableScheduling
@Component
public class FixedTimeTask {

    // 消息重发次数
    public static final int reSendMessageTimes = 3;

    @Autowired
    RedisService redisService;

    @Autowired
    MQSender mqSender;

    @Autowired
    ErrorMsgService errorMsgService;

    /***
     * @Description: 将发送失败的消息进行重发
     *                  30s执行一次
     * @Author: hermanCho
     * @Date: 2020-08-12

     * @return: void
     **/
    @Scheduled(cron = "*/30 * * * * ?")
    public void execute() {
        System.out.println("定时任务:将发送失败的消息进行重发");
        List<String> keys = redisService.scanKeys(MiaoshaKey.getMiaoshaMessage.getPrefix());

        for (String key : keys) {
//            System.out.println("key:" + key);
            String onlyPrefix = MiaoshaKey.getMiaoshaMessage.getSimPrefix();
            int start = key.indexOf(onlyPrefix) + onlyPrefix.length();
            // 提取correlationId
            String correlationId = key.substring(start, key.length());

            KeyPrefix kP = MiaoshaKey.getResendCount;

            // 不存在才设置，保证原子性
            redisService.setnx(kP, correlationId, reSendMessageTimes);

            long res = redisService.decr(kP, correlationId);
            MiaoshaMessage miaoshaMessage = redisService.get(MiaoshaKey.getMiaoshaMessage, correlationId, MiaoshaMessage.class);
            if (res >= 0) {
                log.warn("重发，第" + (reSendMessageTimes - res) + "次，消息Id为" + correlationId);
                CorrelationData correlationData = new CorrelationData(correlationId);
                mqSender.reSendMiaoshaMessage(miaoshaMessage, correlationData);
            } else {
                // 超出次数，从redis移除该消息，并入库人工处理
                redisService.delete(MiaoshaKey.getMiaoshaMessage, correlationId);
                // 记录的次数也移除
                redisService.delete(kP, correlationId);
                // 对下单的限制也要删除
                redisService.delete(MiaoshaKey.robRedisStock, "" + miaoshaMessage.getUser().getId() + "_" + miaoshaMessage.getGoodsId());

                // TODO: 2020-08-12 貌似可以通过retryTemplate实现，不太会用
                // cause 是confirm回调的，可以通过redis拿。也记录在了日志里面。此处不实现。
                String cause = "";
                ErrorMsg errorMsg = new ErrorMsg(correlationId, ErrorMsg.SEND_ERROR, cause, miaoshaMessage.toString());
                errorMsgService.insert(errorMsg);
            }

        }
        System.out.println("定时任务执行完成");
    }
}
