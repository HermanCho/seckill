package com.imooc.miaosha.rabbitmq;

/***
 * @Description: RabbitMQ的错误消息，包括生产端重发超过限制的，消费端重试完仍出错的。
 * @Author: hermanCho
 * @Date: 2020-08-12
 * @Param null:
 * @return: null
 **/
public class ErrorMsg {
    public static final String SEND_ERROR = "发送错误";
    public static final String CONSUME_ERROR = "消费错误";


    private String correlationId;
    // 发送出错还是消费出错 , 换成int效率更高
    private String type;
    private String errorCause;
    private String msg;

    public ErrorMsg() {
    }

    public ErrorMsg(String correlationId, String type, String errorCause, String msg) {
        this.correlationId = correlationId;
        this.type = type;
        this.errorCause = errorCause;
        this.msg = msg;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getErrorCause() {
        return errorCause;
    }

    public void setErrorCause(String errorCause) {
        this.errorCause = errorCause;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }


}
