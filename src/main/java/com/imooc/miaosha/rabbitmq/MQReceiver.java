package com.imooc.miaosha.rabbitmq;

import com.imooc.miaosha.domain.MiaoshaOrder;
import com.imooc.miaosha.domain.MiaoshaUser;
import com.imooc.miaosha.redis.PrefixKey.GoodsKey;
import com.imooc.miaosha.redis.PrefixKey.MiaoshaKey;
import com.imooc.miaosha.redis.RedisService;
import com.imooc.miaosha.service.ErrorMsgService;
import com.imooc.miaosha.service.GoodsService;
import com.imooc.miaosha.service.MiaoshaService;
import com.imooc.miaosha.service.OrderService;
import com.imooc.miaosha.vo.GoodsVo;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service

public class MQReceiver {


    @Autowired
    RedisService redisService;

    @Autowired
    GoodsService goodsService;

    @Autowired
    OrderService orderService;

    @Autowired
    MiaoshaService miaoshaService;


    @Autowired
    RabbitTemplate rabbitTemplate;


    /***
     * @Description: 监听死信队列，人工处理时使用
     * @Author: hermanCho
     * @Date: 2020-08-12
     * @Param msg:
     * @Param channel:
     * @Param messages:
     * @return: void
     **/

    @RabbitListener(queues = MQConfig.DEAD_MSG_QUEUE)
    @RabbitHandler
    public void receiveDeadMsg(String msg, Channel channel, Message messages) throws Exception {
        System.out.println("收到消息:   " + msg);
        try {
            MessageProperties messageProperties = messages.getMessageProperties();
            System.out.println(messageProperties.getDeliveryTag());
            System.out.println(messageProperties.toString());
//            channel.basicAck(messages.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            channel.basicNack(messages.getMessageProperties().getDeliveryTag(), false, false);
        }
    }

    @Autowired
    ErrorMsgService errorMsgService;

    // 失败，允许重回队列的次数
    public static final int reQueueTimes = 3;

    private static Map<String, Integer> errorLogMap = new HashMap<>();

    /***
     * @Description: 对消息的处理：库存<=0，以及重试超过限制，直接拒绝重回，配合死信队列处理
     *                  逻辑可动态调整，但一定要回应消息。
     *
     *                  关于消息幂等性的处理，此处1.根据redis判断是否秒杀
     *         // 2.根据数据库重复key，这是数据库层面上的处理。
     *         // 如果允许重复秒杀的场景，则需要根据correlationId进行判断
     * @Author: hermanCho
     * @Date: 2020-08-12
     * @Param message:
     * @Param channel:
     * @Param messages:
     * @return: void
     **/
    @RabbitHandler
    @RabbitListener(queues = MQConfig.MIAOSHA_QUEUE)
    public void receive(String message, Channel channel, Message messages) throws Exception {
        // 消息幂等性的处理
        String correlatonId = messages.getMessageProperties().getCorrelationId();
        boolean consumed = getMsgConsumed(correlatonId);
        if (consumed) {
            log.warn("该消息已消费过");
            // 已经消费过了，确认该消息
            channel.basicAck(messages.getMessageProperties().getDeliveryTag(), false);
            return;
        }

        // try catch，catch要用，先声明
        MiaoshaMessage mm = null;
        MiaoshaUser user = null;
        try {
            mm = RedisService.stringToBean(message, MiaoshaMessage.class);
            user = mm.getUser();
            long goodsId = mm.getGoodsId();
            System.out.println("消费者1，出队用户" + user.getId() + "，商品" + goodsId);
            GoodsVo goods = goodsService.getGoodsVoByGoodsId(goodsId);

// 区别1：判断库存可省略
//             这里查的是DB，但其实没意义，因为能到这里，必定是已经抢到redis的缓存库存。
//			 最坏情况就是失败，回滚库存，而不会出现库存不足的情况。
//			 本质就是 DB库存 >= redis库存
            int stock = goods.getStockCount();

            if (stock <= 0) {
                String cause = "出队查询库存<=0，库存数量:" + stock;
                channel.basicNack(messages.getMessageProperties().getDeliveryTag(), false, false);
                logErrorMsg(correlatonId, mm, cause);
                return;
            }

            MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(user.getId(), goodsId);
            if (order != null) {
                // 区别2：判断已秒杀到时，进行补偿。
                // 如上文所说，这里要么就整个if不要，要么就进行补偿。不incr补偿直接返回是错误的。
                log.warn(user.getId() + "已买到了" + goodsId + ",进行库存补充");
                redisService.incr(GoodsKey.getMiaoshaGoodsStock, "" + goodsId);
            } else {
                miaoshaService.miaosha(user, goods);
            }
            channel.basicAck(messages.getMessageProperties().getDeliveryTag(), false);
            setMsgConsumed(correlatonId);
        } catch (DuplicateKeyException e) {
            log.warn("订单重复了:" + e.getMessage());
            // 进行库存补偿
            redisService.incr(GoodsKey.getMiaoshaGoodsStock, "" + mm.getGoodsId());
            // ack，认为此时已处理错误，消息也算消费"成功"
            channel.basicAck(messages.getMessageProperties().getDeliveryTag(), false);
            setMsgConsumed(correlatonId);
        } catch (Exception e) {
            // 用手动模式的时候，标准只有确认，而重试超限抛的异常是无法被catch，也无法被全局处理的。
            // 因此方案有两种： 1:换回auto，重试超限直接死信 2.手动记录异常的次数，超了Nack，否则throw

            // 采用第二种方案。直接采用内存标记Map，减少redis压力
            // 但需要注意不同消息间的隔离问题，用correlationId作key
            // 此时可以关闭重试，因为是手动完成的。也可以开着，但需要注意设置的重试次数要 > 手动重试次数，因为无法捕获超限异常
            log.warn("捕获到异常" + e.toString());

            int deadTime = errorLogMap.getOrDefault(correlatonId, 0);
            if (deadTime < 3) {
                errorLogMap.put(correlatonId, deadTime + 1);
                throw e;
            } else {
                //重回次数用完了，requeue = false
                channel.basicNack(messages.getMessageProperties().getDeliveryTag(), false, false);
                String cause = "重试超限,异常：" + e.getClass();
                logErrorMsg(correlatonId, mm, cause);
                // 移除Map，防止内存溢出
                errorLogMap.remove(correlatonId);
                // 消息消费失败，需要把限制下单给取消掉
                redisService.delete(MiaoshaKey.robRedisStock, "" + user.getId() + "_" + mm.getGoodsId());
            }
        }
    }


    /***
     * @Description: 记录异常消息到日志，并入库
     * @Author: hermanCho
     * @Date: 2020-08-12
     * @Param correlationId:
     * @Param miaoshaMessage:
     * @Param errorCause:
     * @return: void
     **/
    private void logErrorMsg(String correlationId, MiaoshaMessage miaoshaMessage, String errorCause) {
        log.warn(errorCause);
        ErrorMsg errorMsg = new ErrorMsg();
        errorMsg.setCorrelationId(correlationId);
        errorMsg.setType(ErrorMsg.CONSUME_ERROR);
        errorMsg.setErrorCause(errorCause);
        errorMsg.setMsg(miaoshaMessage.toString());
        errorMsgService.insert(errorMsg);
    }


    private void setMsgConsumed(String correlationId) {
        redisService.set(MiaoshaKey.isConsumed, "" + correlationId, true);
    }

    private boolean getMsgConsumed(String correlationId) {
        return redisService.exists(MiaoshaKey.isConsumed, "" + correlationId);
    }

}
