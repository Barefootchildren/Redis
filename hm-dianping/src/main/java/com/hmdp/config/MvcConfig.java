package com.hmdp.config;

import com.hmdp.utils.ExpirationInterceptor;
import com.hmdp.utils.LoginInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //刷新生命周期的拦截器
        registry.addInterceptor(new ExpirationInterceptor(redisTemplate)).addPathPatterns("/**").order(0);
        //校验登录状态的拦截器
        registry.addInterceptor(new LoginInterceptor()).addPathPatterns(
                "/shop/**",
                "/voucher/**",
                "/shop-type/**",
                "/upload/**",
                "/blog/hot",
                "/user/code",
                "/user/login"
        ).order(1);
    }
}
