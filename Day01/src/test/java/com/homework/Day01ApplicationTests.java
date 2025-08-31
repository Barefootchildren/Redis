package com.homework;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homework.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class Day01ApplicationTests {
    @Autowired
    private StringRedisTemplate template;
    private static final ObjectMapper mapper=new ObjectMapper();
    @Test
    void insertTest() throws JsonProcessingException {
        User user = new User("张三", 25);
        String json = mapper.writeValueAsString(user);
        template.opsForValue().set("user",json);
        String user1 = template.opsForValue().get("user");
        User readValue = mapper.readValue(user1, User.class);
        System.out.println(readValue);
    }

}
