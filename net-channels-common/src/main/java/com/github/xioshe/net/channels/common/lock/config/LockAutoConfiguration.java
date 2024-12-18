package com.github.xioshe.net.channels.common.lock.config;

import com.github.xioshe.net.channels.common.lock.aspect.LockAspect;
import com.github.xioshe.net.channels.common.lock.core.LocalLockExecutor;
import com.github.xioshe.net.channels.common.lock.core.LockExecutor;
import com.github.xioshe.net.channels.common.lock.core.RedisLockExecutor;
import com.github.xioshe.net.channels.common.lock.template.LockTemplate;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

@AutoConfiguration
@EnableConfigurationProperties(LockProperties.class)
@Conditional(LockingEnabledCondition.class)
public class LockAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.lock", name = "type", havingValue = "LOCAL", matchIfMissing = true)
    public LockExecutor localLockExecutor() {
        return new LocalLockExecutor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.lock", name = "type", havingValue = "REDIS")
    @ConditionalOnClass(RedissonClient.class)
    public LockExecutor redisLockExecutor(RedissonClient redissonClient, LockProperties lockProperties) {
        return new RedisLockExecutor(redissonClient, lockProperties.getKeyStorePrefix());
    }

    @Bean
    public LockTemplate lockTemplate(LockExecutor lockExecutor, LockProperties properties) {
        return new LockTemplate(lockExecutor, properties);
    }

    @Bean
    public LockAspect lockAspect(LockTemplate lockTemplate) {
        return new LockAspect(lockTemplate);
    }
}