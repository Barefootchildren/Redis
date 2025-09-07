package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIDWorker {
    // 2025-01-01 00:00:00 UTC 时间戳
    private static final long BEGIN_TIMESTAMP = LocalDateTime
            .of(2025, 1, 1, 0, 0, 0) // 2025-01-01 00:00:00
            .toEpochSecond(ZoneOffset.UTC);
    //序列号位数
    private static final int COUNT_BITS=32;
    private StringRedisTemplate redisTemplate;
    public RedisIDWorker(StringRedisTemplate template){
        this.redisTemplate=template;
    }
    public long nextID(String keyPrefix){
        //时间戳
        LocalDateTime now = LocalDateTime.now();
        long timestamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;

        //用redis生成序列号保证分布式全局唯一
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = redisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //拼接
        return timestamp<<COUNT_BITS | count;
    }
}
