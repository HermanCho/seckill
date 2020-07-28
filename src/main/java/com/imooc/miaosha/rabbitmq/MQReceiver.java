package com.imooc.miaosha.rabbitmq;

import com.imooc.miaosha.domain.MiaoshaOrder;
import com.imooc.miaosha.domain.MiaoshaUser;
import com.imooc.miaosha.redis.PrefixKey.GoodsKey;
import com.imooc.miaosha.redis.RedisService;
import com.imooc.miaosha.service.GoodsService;
import com.imooc.miaosha.service.MiaoshaService;
import com.imooc.miaosha.service.OrderService;
import com.imooc.miaosha.vo.GoodsVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MQReceiver {

    private static Logger log = LoggerFactory.getLogger(MQReceiver.class);

    @Autowired
    RedisService redisService;

    @Autowired
    GoodsService goodsService;

    @Autowired
    OrderService orderService;

    @Autowired
    MiaoshaService miaoshaService;

    @RabbitListener(queues = MQConfig.MIAOSHA_QUEUE)

    public void receive(String message) {
        log.info("receive message:" + message);
        MiaoshaMessage mm = RedisService.stringToBean(message, MiaoshaMessage.class);
        MiaoshaUser user = mm.getUser();
        long goodsId = mm.getGoodsId();

        GoodsVo goods = goodsService.getGoodsVoByGoodsId(goodsId);

        // 区别1：判断库存可省略

//             这里查的是DB，但其实没意义，因为能到这里，必定是已经抢到redis的缓存库存。
//			 最坏情况就是失败，回滚库存，而不会出现库存不足的情况。
//			 本质就是 DB库存 >= redis库存
        int stock = goods.getStockCount();
        if (stock <= 0) {
            log.info("出队查询库存小于0，不应该出现");
            return;
        }
//	    	int stock = goods.getStockCount();
//	    	if(stock == 0) {
//	    		log.info("卖光了");
//	    		return;
//	    	}else if(stock < 0){
//                log.info("出队查询库存小于0，不应该出现");
//            }

        MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(user.getId(), goodsId);
        if (order != null) {
            // 区别2：判断已秒杀到时，进行补偿。

            // 如上文所说，这里要么就整个if不要，要么就进行补偿。不incr补偿直接返回是错误的。
            redisService.incr(GoodsKey.getMiaoshaGoodsStock, "" + goodsId);
            return;
        }

        miaoshaService.miaosha(user, goods);
    }


//		@RabbitListener(queues=MQConfig.QUEUE)
//		public void receive(String message) {
//			log.info("receive message:"+message);
//		}
//		
//		@RabbitListener(queues=MQConfig.TOPIC_QUEUE1)
//		public void receiveTopic1(String message) {
//			log.info(" topic  queue1 message:"+message);
//		}
//		
//		@RabbitListener(queues=MQConfig.TOPIC_QUEUE2)
//		public void receiveTopic2(String message) {
//			log.info(" topic  queue2 message:"+message);
//		}
//		
//		@RabbitListener(queues=MQConfig.HEADER_QUEUE)
//		public void receiveHeaderQueue(byte[] message) {
//			log.info(" header  queue message:"+new String(message));
//		}
//		

}
