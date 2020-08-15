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

import java.io.IOException;
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
        System.out.println("死信队列收到消息:   " + msg);
        int i = 1 / 0;
        try {
            MessageProperties messageProperties = messages.getMessageProperties();
//            System.out.println(messageProperties.getDeliveryTag());
//            channel.basicAck(messages.getMessageProperties().getDeliveryTag(), false);
        } catch (Exception e) {
            channel.basicNack(messages.getMessageProperties().getDeliveryTag(), false, false);
        }
//        finally {
//            channel.basicNack(messages.getMessageProperties().getDeliveryTag(), false, true);
//        }
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
        // 用于异常超限时，判断秒杀是否完成，进而决定是否补充库存
        boolean miaoshaSuccess = false;
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

                ackAndsetMsgConsumed(channel, messages);
                // 测试用，这个好像没出现过
                String cause = "出队查询库存<=0，库存数量:" + stock;
                logErrorMsg(correlatonId, mm, cause);
                return;
            }

            MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(user.getId(), goodsId);
            if (order != null) {
                // 区别2：判断已秒杀到时，进行补偿。
                // 如上文所说，这里要么就整个if不要，要么就进行补偿。不incr补偿直接返回是错误的。
                log.warn(user.getId() + "已买到了" + goodsId + ",进行库存补充");
                redisService.incr(GoodsKey.getMiaoshaGoodsStock, "" + goodsId);

                ackAndsetMsgConsumed(channel, messages);
                return;
            }
            // else可有可无，上面已经return了。更好理解一点。
            else {
                miaoshaService.miaosha(user, goods);
                miaoshaSuccess = true;

                ackAndsetMsgConsumed(channel, messages);
            }

        } catch (DuplicateKeyException e) {
            log.warn("订单重复了:" + e.getMessage());
            // 进行库存补偿
            redisService.incr(GoodsKey.getMiaoshaGoodsStock, "" + mm.getGoodsId());
            // ack，认为此时已处理错误，消息也算消费"成功"
            ackAndsetMsgConsumed(channel, messages);

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
                // 消费失败，进行库存补偿
                if(miaoshaSuccess){
                    System.out.println("补库存");
                    redisService.incr(GoodsKey.getMiaoshaGoodsStock, "" + mm.getGoodsId());
                }

                // 别忘了标记已消费该消息
                ackAndsetMsgConsumed(channel, messages);

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


//    @RabbitHandler
//    @RabbitListener(queues = MQConfig.MIAOSHA_QUEUE)
//    public void receive(String message, Channel channel, Message messages) throws Exception {
//        // try住整个代码，防止漏异常。如果catch需要的try中的变量，则提前声明就好了
//        try {
//            // 消息幂等性的处理
//            String correlatonId = messages.getMessageProperties().getCorrelationId();
//            //根据correlationId判断消息是否被消费过
//            if (消息已经被消费过) {
//                return;
//            }
//            if (库存不足) {
//                return;
//            }
//            if (已经秒杀到) {// 重复下单
//                补充库存
//                return;
//            }
//
//            //进行原子操作：1.库存减1，2.下订单，3.写入秒杀订单--->是一个事务
//            miaoshaService.miaosha(user, goodsvo);
//        } catch (DuplicateKeyException e) {
//            补充库存
//        } catch (Exception e) {
//            if(重试超出限制){
//                补充库存
//            }
//        }
//    }


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


    private void ackAndsetMsgConsumed(Channel channel, Message messages) throws IOException {
        String correlatonId = messages.getMessageProperties().getCorrelationId();
        channel.basicAck(messages.getMessageProperties().getDeliveryTag(), false);
        redisService.set(MiaoshaKey.isConsumed, correlatonId, true);
    }


    private boolean getMsgConsumed(String correlationId) {
        return redisService.exists(MiaoshaKey.isConsumed, "" + correlationId);
    }

}
