package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import io.lettuce.core.RedisClient;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIDWorker redisIDWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate redisTemplate;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIDWorker.nextID("order");
        Long result = redisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        //判断结果是否为0
        if(r!=0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        return Result.ok(orderId);
    }

    //单开线程池处理消息队列的信息
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    //获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"), StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)), StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    //判断订单是否为空，没有就继续循环
                    if(list==null||list.isEmpty()){
                        continue;
                    }
                    //解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //创建订单
                    createVoucherOrder(voucherOrder);
                    //XACK确认消息
                    redisTemplate.opsForStream().acknowledge("stream.orders","g1",record.getId());
                } catch (Exception e){
                    log.error("订单处理发生异常",e);
                    handlePendingList();
                }
            }
        }
        private void handlePendingList(){
            while (true){
                try {
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    if (list==null||list.isEmpty()){
                        break;
                    }
                    //解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //创建订单
                    createVoucherOrder(voucherOrder);
                    //XACK确认消息
                    redisTemplate.opsForStream().acknowledge("stream.orders","g1",record.getId());
                }catch (Exception e){
                    log.error("订单处理发生异常",e);
                }
            }
        }
    }
    private void createVoucherOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //获取锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean b = lock.tryLock();
        //判断锁获取
        if(!b){
            //获取失败
            log.error("不允许重复下单");
            return;
        }
        try {
            //查询订单
            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //判断是否已经购买过
            if (count>0){
                log.error("不允许重复下单");
                return;
            }

            //扣减库存
            boolean update = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
            //扣减失败
            if(!update){
             log.error("库存不足");
             return;
            }
            save(voucherOrder);
        }finally {
            lock.unlock();
        }
    }
}
