package me.hecheng.pay.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author hecheng 2020-05-17 15:15
 * @description 分布式锁
 */
@Component
@Slf4j
public class DistributedLock {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    public static final String REDIS_LOCL = "REDIS_LOCL:";

    /**
     * 创建锁
     *
     * @param lockKey
     * @param acquireTimeout
     * @param timeOut
     * @return
     */
    public String lock(String lockKey, Long acquireTimeout, Long timeOut) {
        try {
            String identifierValue = UUID.randomUUID().toString();
            String lockName = REDIS_LOCL + lockKey;
            Long endTime = System.currentTimeMillis() + acquireTimeout * 1000;
            while (System.currentTimeMillis() < endTime) {
                if (redisTemplate.opsForValue().setIfAbsent(lockName, identifierValue, timeOut, TimeUnit.SECONDS)) {
                    return identifierValue;
                }
            }
        } catch (Exception e) {
            log.error("创建锁失败", e);
            throw new RuntimeException(e);
        }
        return null;
    }

    /**
     * 释放锁
     *
     * @param lockKey
     * @param identifier
     * @return
     */
    public boolean unLock(String lockKey, String identifier) {
        try {
            String lockName = REDIS_LOCL + lockKey;
            if (identifier.equals(redisTemplate.opsForValue().get(lockName))) {
                return redisTemplate.delete(lockName);
            }
        } catch (Exception e) {
            log.error("释放锁失败", e);
            throw new RuntimeException(e);
        }
        return false;
    }

}
