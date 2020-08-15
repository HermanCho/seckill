package com.imooc.miaosha.rabbitmq;

import com.imooc.miaosha.dao.ErrorMsgDao;
import com.imooc.miaosha.redis.PrefixKey.MiaoshaKey;
import com.imooc.miaosha.redis.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
public class MQConfig {

    public static final String MIAOSHA_QUEUE = "miaosha.queue";

    public static final String MIAOSHA_EXCHANGE = "miaosha.exchange";

    public static final String MIAOSHA_EXCHANGE_BAK = "miaosha.bakExchange";

    // fanout模式，路由键无所谓
    public static final String MIAOSHA_ROUTING_KEY = "";

    //    -------- 死锁队列-----------------
    public static final String DEAD_EXCHANGE = "dead_exchage";
    public static final String DEAD_MSG_QUEUE = "deadMsg.queue";


    @Bean
    public Queue queue() {

        Map<String, Object> arguments = new HashMap<String, Object>();
        arguments.put("x-dead-letter-exchange", DEAD_EXCHANGE);

        // 设置队列消息 过期time
        //arguments.put("x-message-ttl", 10000);
        Queue queue = new Queue(MIAOSHA_QUEUE, true, false, false, arguments);

        return queue;
    }


    /***
     * @Description: 采用 Fanout模式，效率最高
     * @Author: hermanCho
     * @Date: 2020-08-11

     * @return: org.springframework.amqp.core.FanoutExchange
     **/

    @Bean
    public FanoutExchange secKillExchange() {
//        指定备份交换机，消息无法被路由的另一种解决方案
//         Map<String, Object> argsMap = new HashMap<String, Object>();
//        argsMap.put("alternate-exchange", MIAOSHA_EXCHANGE_BAK);
//        DirectExchange directExchange = new DirectExchange(MIAOSHA_EXCHANGE,true,false,argsMap);

        // durable，持久化 .默认true
        // autoDelete : 无队列绑定时自动删除 （为true就不会有无法被路由的问题）,默认false
        // 显式声明，容易理解
        return new FanoutExchange(MIAOSHA_EXCHANGE, true, false);
    }


    @Bean
    public Binding binding() {
        Binding binding = BindingBuilder.bind(queue()).to(secKillExchange());
        return binding;
    }


    //    ---------  死信交换机声明 ---------
    @Bean
    public FanoutExchange deadExchange() {
        return new FanoutExchange(DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue deadMessageQueue() {
        return new Queue(DEAD_MSG_QUEUE);
    }

    @Bean
    public Binding deadBinding() {
        Binding binding = BindingBuilder.bind(deadMessageQueue()).to(deadExchange());
        return binding;
    }


    //    -------------   分界线，下面设置rabbitTemplate的回调接口等 ----------------


    @Autowired
    RedisService redisService;

    @Autowired
    MQSender mqSender;


    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setConfirmCallback(confirmCallback());
        // 两者都必须设置，才能触发returnCallBack
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnCallback(returnCallback());
        rabbitTemplate.setBeforePublishPostProcessors(correlationIdProcessor());
        return rabbitTemplate;
    }


    @Bean
    /***
     * @Description: 消息发送到交换器时回调
     * @Author: hermanCho
     * @Date: 2020-08-12
     * @return: org.springframework.amqp.rabbit.core.RabbitTemplate.ConfirmCallback
     **/
    public RabbitTemplate.ConfirmCallback confirmCallback() {
        RabbitTemplate.ConfirmCallback confirmCallback = new RabbitTemplate.ConfirmCallback() {
            @Override
            public void confirm(CorrelationData correlationData, boolean ack, String cause) {
                log.info("---------触发confirm-----------");
//                System.out.println("id：" + correlationData.getId());
//                System.out.println("  ack：" + ack);
//                System.out.println("cause：" + cause);

                // 成功，删除记录的key
                if (ack) {
                    if (correlationData != null) {
                        redisService.delete(MiaoshaKey.getMiaoshaMessage, correlationData.getId());
                    }

                } else {
                    // 由定时任务完成，见rabbitmq.FixedTimeTask
                }
//                log.info("----------confirm触发完成----------");
            }
        };
        return confirmCallback;
    }


    @Autowired
    ErrorMsgDao errorMsgDao;

    //
    @Bean
    /***
     * @Description: 消息发送到交换器，但无队列与交换器绑定时回调。
     *                 一样会触发confirmCallback，不过ack = true
     * @Author: hermanCho
     * @Date: 2020-08-12
     * @return: org.springframework.amqp.rabbit.core.RabbitTemplate.ReturnCallback
     **/

    public RabbitTemplate.ReturnCallback returnCallback() {
        RabbitTemplate.ReturnCallback returnCallback = new RabbitTemplate.ReturnCallback() {
            @Override
            public void returnedMessage(Message message, int replyCode, String replyText, String exchange, String routingKey) {

                log.error("触发returnCallBack");
                // 记录入库，其实这时就是运维问题了，这方面就不做库存补偿的了。简单记录一下
                ErrorMsg errorMsg = new ErrorMsg();
                errorMsg.setType("发送，消息无法被路由");
                errorMsg.setCorrelationId(message.getMessageProperties().getCorrelationId());
                errorMsg.setErrorCause(replyText);
                errorMsgDao.insert(errorMsg);

//                log.info("message:" + message.toString());
//                log.info("replyCode:" + replyCode);
//                log.info("replyText:" + replyText);
//                log.info("exchange:" + exchange);
//                log.info("routingKey:" + routingKey);
//                log.info("触发returnCallBack完毕");
            }
        };
        return returnCallback;
    }

    @Bean
    /***
     * @Description: 绑定correlationId、以及消息持久化
     * @Author: hermanCho
     * @Date: 2020-08-12
     * @return: org.springframework.amqp.core.MessagePostProcessor
     **/

    public MessagePostProcessor correlationIdProcessor() {
        MessagePostProcessor messagePostProcessor = new MessagePostProcessor() {
            @Override
            public Message postProcessMessage(Message message, Correlation correlation) {
                MessageProperties messageProperties = message.getMessageProperties();

                if (correlation instanceof CorrelationData) {
                    String correlationId = ((CorrelationData) correlation).getId();
                    messageProperties.setCorrelationId(correlationId);
                }
                // 持久化处理
                messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }

            @Override
            public Message postProcessMessage(Message message) throws AmqpException {
                MessageProperties messageProperties = message.getMessageProperties();
                messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }
        };
        return messagePostProcessor;
    }


    // ----------------------- 分界线，下述代码是消费异常重发的"尝试"代码，没成功 ------------------------------------
    // ##############################################################################################################

//    @Autowired
//    RetryTemplate retryTemplate;
//
//    @Bean
//    public RetryListener retryListener() {
//        return new RetryListener() {
//            @Override
//            public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
//                System.out.println("aaaa");
//                return false;
//            }
//
//            @Override
//            public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
//                System.out.println("bbb");
//            }
//
//            @Override
//            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
//                System.out.println("ccc");
//            }
//        };
//    }
//
//    @PostConstruct
//    public void init() {
//        retryTemplate.registerListener(retryListener());
//    }
//
//
//    @Bean
//    public RabbitListenerErrorHandler rabbitListenerErrorHandler() {
//        return new RabbitListenerErrorHandler() {
//
//            @Override
//            public Object handleError(Message amqpMessage,
//                                      org.springframework.messaging.Message<?> message,
//                                      ListenerExecutionFailedException exception) throws Exception {
//                MessageHeaders messageHeaders = message.getHeaders();
//                // 通过debug找到的这个key字段，可以获取消费者中的channel，进而响应
//                Channel channel = messageHeaders.get("amqp_channel", Channel.class);
//                channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
//                // 或者return null;
//                throw exception;
//            }
//        };
//    }
//
//
//    @Bean
//    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
//
//        return new RepublishMessageRecoverer(rabbitTemplate, DEAD_EXCHANGE);
//    }


}
