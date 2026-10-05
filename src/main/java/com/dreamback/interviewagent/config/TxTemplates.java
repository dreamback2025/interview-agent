package com.dreamback.interviewagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 事务边界模板：只读 / 读写各一份。
 *
 * <p><b>为什么需要它</b>：LLM 一次调用 20~40 秒，把它包在 {@code @Transactional} 里
 * 就等于让一个数据库连接空转几十秒 —— 连接池默认只有 10 个，十来个并发就打满，
 * 后续请求全部卡在 {@code getConnection()} 上直到超时。所以事务必须拆成
 * 「读一段 / 算一段 / 写一段」，把慢的 IO 留在事务外。
 *
 * <p><b>为什么不用 {@code @Transactional} 私有方法</b>：拆完之后，编排方法内部调用
 * 同类私有事务方法属于 Spring AOP 自调用，代理不生效，事务根本不会开启。
 * 用 TransactionTemplate 显式划定边界，自调用问题自动消失。
 */
@Configuration
public class TxTemplates {

    /** 读事务：只加载数据，不 flush，开销更小 */
    @Bean
    public TransactionTemplate roTransactionTemplate(PlatformTransactionManager tm) {
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setReadOnly(true);
        t.setName("ro");
        return t;
    }

    /** 写事务：只包落库动作，不含任何远程调用 */
    @Bean
    public TransactionTemplate rwTransactionTemplate(PlatformTransactionManager tm) {
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setName("rw");
        return t;
    }
}
