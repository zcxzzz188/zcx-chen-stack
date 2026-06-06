package com.zcx.chenstack.redis;

import com.zcx.chenstack.utils.MyThreadFactory;
import jakarta.annotation.Resource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author zcx
 * @since 2025-08-23
 */
@Service
public class RedisService implements ApplicationRunner {

    @Resource
    private RedisComponent redisComponent;

    ExecutorService executorService = new ThreadPoolExecutor(
            2, 4, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(500),
            new MyThreadFactory("RedisService")
    );

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 图片缓存初始化逻辑已清理
    }

}
