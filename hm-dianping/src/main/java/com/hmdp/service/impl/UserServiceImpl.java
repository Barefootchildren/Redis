package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate template;
    @Override
    public Result sendCode(String phone) {
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //格式不对报错
            return Result.fail("手机号格式错误");
        }
        //格式对了生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到redis
        template.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone,code,
                RedisConstants.LOGIN_CODE_TTL,TimeUnit.MINUTES);
        //模拟发送验证码
        log.debug("验证码为："+code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        //验证手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //格式不对报错
            return Result.fail("手机号格式错误");
        }
        //校验验证码
        String cacheCode = template.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode==null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //一致就开始查询用户信息
        User user = query().eq("phone", phone).one();
        //不存在就新建一个
        if(user==null){
            user=creatUser(phone);
        }

        //保存用户信息到redis中
        //生成随机token作为这个user对象的key
        String uuid = UUID.randomUUID().toString(true);
        //将User对象拷贝到UserDTO防止隐私信息泄露在前端,并转成map类型（方便存入redis）
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userDTOMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((filedName, filedValue) -> filedValue.toString()));
        //存入redis
        String key = RedisConstants.LOGIN_USER_KEY + uuid;
        template.opsForHash().putAll(key,userDTOMap);
        //设置有效期
        template.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(uuid);
    }

    private User creatUser(String phone) {
        User user = new User();
        user.setPhone(phone).setNickName(RedisConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
