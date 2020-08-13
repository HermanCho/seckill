package com.imooc.miaosha.rabbitmq;

import com.imooc.miaosha.domain.MiaoshaUser;

public class MiaoshaMessage {
    // TODO: 2020-08-12 其实记录userID就可以了
    private MiaoshaUser user;
    private long goodsId;

    public MiaoshaUser getUser() {
        return user;
    }

    public void setUser(MiaoshaUser user) {
        this.user = user;
    }

    public long getGoodsId() {
        return goodsId;
    }

    public void setGoodsId(long goodsId) {
        this.goodsId = goodsId;
    }

    @Override
    public String toString() {

        return "MiaoshaMessage{" +
                (
                        user == null ? "user为空" : ("userId=" + user.getId())
                )
                +
                ", goodsId=" + goodsId +
                '}';
    }
}
