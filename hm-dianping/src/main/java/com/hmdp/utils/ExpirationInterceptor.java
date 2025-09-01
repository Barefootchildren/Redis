package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ExpirationInterceptor implements HandlerInterceptor {
    private StringRedisTemplate redisTemplate;
    public ExpirationInterceptor(StringRedisTemplate template){
        this.redisTemplate=template;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //第一个拦截器，只做刷新redis数据生命周期用
        //获取UUID
        String uuid = request.getHeader("authorization");
        //这只是第一个拦截器，即使为空也放行
        if(StrUtil.isBlank(uuid)){
            return true;
        }
        //获取redis数据
        String key = RedisConstants.LOGIN_USER_KEY + uuid;
        Map<Object, Object> userDTOMap = redisTemplate.opsForHash().entries(key);
        //判断是否为空
        if(userDTOMap.isEmpty()){
            return true;
        }
        //转为UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userDTOMap, new UserDTO(), false);
        //保存到TreadLocal
        UserHolder.saveUser(userDTO);
        //刷新有效期
        redisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
