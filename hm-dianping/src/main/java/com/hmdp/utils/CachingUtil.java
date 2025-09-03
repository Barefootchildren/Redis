package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CachingUtil {
    private final StringRedisTemplate redisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    public CachingUtil(StringRedisTemplate redisTemplate){
        this.redisTemplate=redisTemplate;
    }
    //数据写入Redis缓存
    public void set(String key, Object value, Long time, TimeUnit unit){
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    //逻辑过期方法的预热
    public void preheating(String key,Object value,Long time,TimeUnit timeUnit){
        //给实体对象加入逻辑生存时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        //写入Redis缓存
        redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }
    //获取锁
    private boolean getLock(String key){
        Boolean b = redisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }
    //释放锁
    private void unLock(String key){
        redisTemplate.delete(key);
    }
    //解决缓存穿透
    public <R,ID> R cachePenetration(String keyPrefix, ID id, Class<R> type, Function<ID,R> queryFunctino,Long time,TimeUnit unit){
        //拼接Key
        String key=keyPrefix+id;
        //查询缓存
        String jsonValue = redisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isNotBlank(jsonValue)){
            //存在直接返回
            return JSONUtil.toBean(jsonValue,type);
        }
        //判断存在的是否是空值（因为为了解决穿透会缓存一个空值）
        if(jsonValue!=null){
            return null;
        }
        //不存在，先查询
        R object = queryFunctino.apply(id);
        //数据库中不存在，返回错误
        if(object==null){
            //先把空值写入缓存，为了解决穿透
            redisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回错误
            return null;
        }
        //数据库中存在，写入缓存
        set(key,object,time,unit);
        return object;
    }
    //使用互斥锁解决缓存击穿
    public <R,ID>R solveBreakdownsWithMutexes(String keyPrefix,ID id,Class<R> type,Function<ID,R> queryFunction,Long time,TimeUnit unit){
        //拼接Key
        String key=keyPrefix+id;
        //查询缓存
        String jsonValue = redisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isNotBlank(jsonValue)){
            //存在直接返回
            return JSONUtil.toBean(jsonValue,type);
        }
        //判断存在的是否是空值（因为为了解决穿透会缓存一个空值）
        if(jsonValue!=null){
            return null;
        }
        //重建缓存
        R r=null;
        //获取互斥锁
        String keyLock=keyPrefix+id;
        boolean lock = getLock(keyLock);
        try {

            //如果获取锁失败则重试并休眠
            if(!lock){
                Thread.sleep(60);
                return solveBreakdownsWithMutexes(keyPrefix,id,type,queryFunction,time,unit);
            }
            //获取锁成功，开始重建缓存
            //查询
            r = queryFunction.apply(id);
            //不存在，返回错误并写入空值缓存
            if(r==null){
                redisTemplate.opsForValue().set(key,"", RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //存在，写入缓存
            set(key,r,time,unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(keyLock);
        }
        return r;
    }
    //使用逻辑过期解决缓存击穿
    public <R,ID>R resolveBreakdownsWithLogicalExpiration(String keyPrefix,ID id,Class<R> type,Function<ID,R> queryFunction,Long time,TimeUnit unit) {
        //拼接Key
        String key = keyPrefix + id;
        //查询缓存
        String jsonValue = redisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(jsonValue)) {
            //不存在直接返回
            return null;
        }
        //存在，先反序列化为对象
        RedisData redisData = JSONUtil.toBean(jsonValue, RedisData.class);
        R r=JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回信息
            return r;
        }
        //过期，重建缓存
        //获取互斥锁
            String keyLock = RedisConstants.LOCK_SHOP_KEY + id;
            boolean lock = getLock(keyLock);
            //获取锁成功
            if(lock){
                //开启新线程，重建缓存
                CACHE_REBUILD_EXECUTOR.submit(()-> {
                    try {
                        //查询
                        R apply = queryFunction.apply(id);
                        //重建缓存
                        preheating(key, apply, time, unit);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }finally {
                        unLock(keyLock);
                    }
                });
        }
            //返回过期信息
            return r;
    }
}
