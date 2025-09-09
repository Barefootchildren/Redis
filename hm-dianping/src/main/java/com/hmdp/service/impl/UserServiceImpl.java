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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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
@Slf4j
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

    @Override
    public Result sign() {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //key年月后缀
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //拼接key
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入redis
        template.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //key年月后缀
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //拼接key
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月到今天为止的所有签到记录
        List<Long> result = template.opsForValue().bitField(key, BitFieldSubCommands.create().get(
                BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)
        ).valueAt(0));
        //签到结果非空判断
        if (result==null||result.isEmpty()){
            return Result.ok(0);
        }
        //将签到记录提取成Long形
        Long num = result.get(0);
        //num非空判断
        if (num==null||num==0){
            return Result.ok(0);
        }
        //循环遍历，查出连续签到天数
        int count=0;
        while (true){
            //跟1做与运算，判断这个bit位是否为0
            if ((num&1)==0){
                //为0说明没签到，遍历结束
                break;
            }else {
                //不为0则说明签到了，计数器加1
                count++;
            }
            //数字右移一位，也就是抛弃最后一位数，让下一位数来做判断
            num>>>=1;
        }
        return Result.ok(count);
    }

    private User creatUser(String phone) {
        User user = new User();
        user.setPhone(phone).setNickName(RedisConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
