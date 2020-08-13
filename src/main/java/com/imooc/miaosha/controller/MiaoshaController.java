package com.imooc.miaosha.controller;

import com.imooc.miaosha.access.AccessLimit;
import com.imooc.miaosha.domain.MiaoshaOrder;
import com.imooc.miaosha.domain.MiaoshaUser;
import com.imooc.miaosha.rabbitmq.MQSender;
import com.imooc.miaosha.rabbitmq.MiaoshaMessage;
import com.imooc.miaosha.redis.PrefixKey.GoodsKey;
import com.imooc.miaosha.redis.PrefixKey.MiaoshaKey;
import com.imooc.miaosha.redis.RedisService;
import com.imooc.miaosha.result.CodeMsg;
import com.imooc.miaosha.result.Result;
import com.imooc.miaosha.service.GoodsService;
import com.imooc.miaosha.service.MiaoshaService;
import com.imooc.miaosha.service.MiaoshaUserService;
import com.imooc.miaosha.service.OrderService;
import com.imooc.miaosha.vo.GoodsVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;

@Controller
@RequestMapping("/miaosha")
public class MiaoshaController implements InitializingBean {

    private static Logger log = LoggerFactory.getLogger(MiaoshaController.class);

    @Autowired
    MiaoshaUserService userService;

    @Autowired
    RedisService redisService;

    @Autowired
    GoodsService goodsService;

    @Autowired
    OrderService orderService;

    @Autowired
    MiaoshaService miaoshaService;

    @Autowired
    MQSender sender;

    private HashMap<Long, Boolean> localOverMap = new HashMap<Long, Boolean>();

    /**
     * 系统初始化
     */
    public void afterPropertiesSet() throws Exception {
        // 方便测试，每次重启先恢复库存
        this.reset();
        List<GoodsVo> goodsList = goodsService.listGoodsVo();
        if (goodsList == null) {
            return;
        }
        for (GoodsVo goods : goodsList) {
            redisService.set(GoodsKey.getMiaoshaGoodsStock, "" + goods.getId(), goods.getStockCount());
            localOverMap.put(goods.getId(), false);
        }
    }

//    @RequestMapping(value = "/test")
//    @ResponseBody
//    public Result<String> test() {
//       redisService.lock(LockKey.Lock,"","a",
//               LockKey.Lock.expireSeconds() * 1000L,5 * 1000L);
//
//    }

    @RequestMapping(value = "/createOrder")
    @ResponseBody
    public Result<String> createOrder() {
        MiaoshaUser user = new MiaoshaUser();
        user.setId(11111111111L);
        GoodsVo goods = new GoodsVo();
        goods.setId(1L);
        goods.setGoodsName("iphonex");
        goods.setMiaoshaPrice(Double.valueOf(1000));
//        orderService.createOrder(user,goods);
        miaoshaService.miaosha(user, goods);
        return Result.success("success");
    }


    @RequestMapping(value = "/reset", method = RequestMethod.GET)
    @ResponseBody
    public Result<Boolean> reset() {
        List<GoodsVo> goodsList = goodsService.listGoodsVo();
        for (GoodsVo goods : goodsList) {
            int stockCount = 1;
            goods.setStockCount(stockCount);
            redisService.set(GoodsKey.getMiaoshaGoodsStock, "" + goods.getId(), stockCount);
            localOverMap.put(goods.getId(), false);
        }
        miaoshaService.reset(goodsList);
        // 删除前缀key
        redisService.reset();
        return Result.success(true);
    }

    /**
     * QPS:1306
     * 5000 * 10
     * QPS: 2114
     */
//    @RequestMapping(value = "/{path}/do_miaosha", method = RequestMethod.POST)
    @RequestMapping(value = {"/do_miaosha", "/{path}/do_miaosha"})
    @ResponseBody
    public Result<Integer> miaosha(Model model, MiaoshaUser user,
                                   @RequestParam("goodsId") long goodsId,
                                   @PathVariable(value = "path", required = false) String path) {
        model.addAttribute("user", user);
        //验证path ，为方便测试，允许为null。正常是去掉if，不允许为null
        if(path != null){
            boolean check = miaoshaService.checkPath(user, goodsId, path);
            if (!check) {
                return Result.error(CodeMsg.REQUEST_ILLEGAL);
            }
        }

        //内存标记，减少redis访问
        boolean over = localOverMap.get(goodsId);
        if (over) {
//            System.out.println("秒杀商品已不足");
            return Result.error(CodeMsg.MIAO_SHA_OVER);
        }

        // 判断是否已经秒杀到了
        // 在预减库存前完成，可以防止用户完成秒杀，但多次点击
        MiaoshaOrder order = orderService.getMiaoshaOrderByUserIdGoodsId(user.getId(), goodsId);
        if (order != null) {
            log.debug(user.getId() + "已经买过商品：" + goodsId);
            return Result.error(CodeMsg.REPEATE_MIAOSHA);
        }

        // 原来的实现有缺陷，就是redis的库存会被清空，后续虽然能补充成功。
        // 但对其他消费者已经没有机会入队，相当于被同一人大量"锁单"。
        // 提供解决方案：直接记录，让一个消费者只能抢到一个redis库存。在抢redis库存前，先判断一遍
        // 但问题是，出现异常时，需要删除key。包括重发超限、消费出错等，容易漏删。
        // 还有问题是看业务场景，因为已经限制了
        // TODO: 2020-08-13 这样限制后，消费端是否不可能出现重复订单？
        boolean b = redisService.setnx(MiaoshaKey.robRedisStock, "" + user.getId() + "_" + goodsId, true);
        if (!b) {
            log.debug(user.getId() + "已下单" + goodsId + "需等待结果");
            return Result.error(CodeMsg.MIAOSHA_LIMIT);
        }

        //预减库存,decr：返回-1后的结果
        long stock = redisService.decr(GoodsKey.getMiaoshaGoodsStock, "" + goodsId);//10
        if (stock < 0) {
            //进行补偿，上面decr了。
            // TODO: 2020-08-13 可以优化成Lua脚本，先判断数量再减，Lua保证原子性。就不用补偿了
            redisService.incr(GoodsKey.getMiaoshaGoodsStock, "" + goodsId);
            localOverMap.put(goodsId, true);
            System.out.println("秒杀商品卖完了，设置为结束");
            return Result.error(CodeMsg.MIAO_SHA_OVER);
        }

        System.out.println(user.getId() + "抢到redis，商品" + goodsId + "，还剩" + stock);


        //入队
        MiaoshaMessage mm = new MiaoshaMessage();
        mm.setUser(user);
        mm.setGoodsId(goodsId);
        sender.sendMiaoshaMessage(mm);
        return Result.success(0);//排队
    }

    /**
     * orderId：成功
     * -1：秒杀失败
     * 0： 排队中
     */
    @RequestMapping(value = "/result", method = RequestMethod.GET)
    @ResponseBody
    public Result<Long> miaoshaResult(Model model, MiaoshaUser user,
                                      @RequestParam("goodsId") long goodsId) {
        model.addAttribute("user", user);
        long result = miaoshaService.getMiaoshaResult(user.getId(), goodsId);
        return Result.success(result);
    }


    @AccessLimit(seconds = 5, maxCount = 5)
    @RequestMapping(value = "/path", method = RequestMethod.GET)
    @ResponseBody
    public Result<String> getMiaoshaPath(HttpServletRequest request, MiaoshaUser user,
                                         @RequestParam("goodsId") long goodsId,
                                         @RequestParam(value = "verifyCode", defaultValue = "0") int verifyCode) {

//        简单去掉验证码环节
//        boolean check = miaoshaService.checkVerifyCode(user, goodsId, verifyCode);
//        if (!check) {
//            return Result.error(CodeMsg.REQUEST_ILLEGAL);
//        }

        String path = miaoshaService.createMiaoshaPath(user, goodsId);
        return Result.success(path);
    }


    @RequestMapping(value = "/verifyCode", method = RequestMethod.GET)
    @ResponseBody
    public Result<String> getMiaoshaVerifyCod(HttpServletResponse response, MiaoshaUser user,
                                              @RequestParam("goodsId") long goodsId) {
        try {
            BufferedImage image = miaoshaService.createVerifyCode(user, goodsId);
            OutputStream out = response.getOutputStream();
            ImageIO.write(image, "JPEG", out);
            out.flush();
            out.close();
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(CodeMsg.MIAOSHA_FAIL);
        }
    }


}
