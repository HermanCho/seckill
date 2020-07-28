package com.imooc.miaosha.exception;

import com.alibaba.fastjson.JSON;
import com.imooc.miaosha.redis.PrefixKey.GoodsKey;
import com.imooc.miaosha.redis.RedisService;
import com.imooc.miaosha.result.CodeMsg;
import com.imooc.miaosha.result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.util.List;

@ControllerAdvice
@ResponseBody
public class GlobalExceptionHandler {

    @Autowired
    RedisService redisService;

    @ExceptionHandler(value = Exception.class)
//    public Result<String> exceptionHandler(HttpServletRequest request, Exception e) {
    public Result<String> exceptionHandler(HttpServletResponse response, Exception e) {
        e.printStackTrace();
        if (e instanceof GlobalException) {
            GlobalException ex = (GlobalException) e;
//            render(response, ex.getCm());
//            System.out.println("渲染完毕");
            return Result.error(ex.getCm());
        } else if (e instanceof BindException) {
            BindException ex = (BindException) e;
            List<ObjectError> errors = ex.getAllErrors();
            ObjectError error = errors.get(0);
            String msg = error.getDefaultMessage();
            return Result.error(CodeMsg.BIND_ERROR.fillArgs(msg));
        } else {
            return Result.error(CodeMsg.SERVER_ERROR);
        }
    }

    @ExceptionHandler(value = DuplicateKeyException.class)
    public Result<String> DuplicateKeyExceptionHandler(HttpServletResponse response, Exception e) {
        DuplicateKeyException ex = (DuplicateKeyException) e;
        int goodsId = Integer.parseInt(ex.getMessage());
        // 补偿
        redisService.incr(GoodsKey.getMiaoshaGoodsStock, "" + goodsId);
        return Result.error(CodeMsg.REPEATE_MIAOSHA);

    }


    private void render(HttpServletResponse response, CodeMsg cm) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        OutputStream out = response.getOutputStream();
        String str = JSON.toJSONString(Result.error(cm));
        out.write(str.getBytes("UTF-8"));
        out.flush();
        out.close();
    }
}
