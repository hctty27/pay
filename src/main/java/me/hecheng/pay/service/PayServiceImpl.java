package me.hecheng.pay.service;

import lombok.extern.slf4j.Slf4j;
import me.hecheng.pay.component.DistributedLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * @author hecheng 2020-05-17 15:40
 * @description
 */
@Service
@Slf4j
public class PayServiceImpl implements PayService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DistributedLock distributedLock;

    //订单前缀
    private static final String pre = "HC";
    //锁名
    private static final String GENERATE_ORDER = "GENERATE_ORDER";

    /**
     * 获取订单编号
     *
     * @return
     */
    @Override
    public String getOrderId() {
        String lock = distributedLock.lock(GENERATE_ORDER, 10000L, 3000L);
        if (Objects.nonNull(lock)) {
            try {
                //生成当天前缀
                String yyyyMMdd = DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDate.now());
                String prefix = pre + yyyyMMdd;

                //查询今天是否存在订单
                String sql = "SELECT RIGHT(o.order_id,4) FROM `order` o WHERE o.order_id LIKE ? ORDER BY o.order_id DESC LIMIT 1";
                Integer orderId = null;
                try {
                    orderId = jdbcTemplate.queryForObject(sql, Integer.class, prefix + "%");
                } catch (Exception e) {
                }

                if (Objects.isNull(orderId)) {
                    prefix += "0001";
                } else {
                    prefix += String.format("%04d", orderId + 1);
                }

                //插入
                String insertSql = "INSERT INTO `order`(`id`, `order_id`) VALUES (?,?)";
                jdbcTemplate.update(insertSql, System.currentTimeMillis(), prefix);

                return prefix;
            } catch (Exception e) {
                log.error("创建订单失败");
                throw new RuntimeException(e);
            } finally {
                distributedLock.unLock(GENERATE_ORDER,lock);
            }
        } else {
            throw new RuntimeException("创建锁超时");
        }
    }

}
