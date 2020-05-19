/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.registry.redis;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ExecutorUtil;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.support.FailbackRegistry;
import com.alibaba.dubbo.rpc.RpcException;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RedisRegistry，基于Redis实现的注册中心实现类
 * <ul>
 *     <li>使用Redis的hash结构存储数据，主key为服务名和类型拼接路径【root/service/type】，Map中的key为URL地址，Map中的Value为过期时间【用于判断脏数据，脏数据由监控中心删除】</li>
 *     <li>不使用Redis的自动过期机制，而是通过监控中心，实现过期机制。因为Redis key自动过期时，不存在相应的事件通知</li>
 *     <li>服务提供者和消费者，定时延长其注册的URL地址的过期时间</li>
 *     <li>使用Redis的Publish/Subscribe事件通知数据变更，通过事件的值区分事件类型： register,unregister</li>
 *
 * </ul>
 */
public class RedisRegistry extends FailbackRegistry {

    private static final Logger logger = LoggerFactory.getLogger(RedisRegistry.class);
    /**
     * 默认端口
     */
    private static final int DEFAULT_REDIS_PORT = 6379;
    /**
     * 默认redis的key的根据节点
     */
    private final static String DEFAULT_ROOT = "dubbo";

    /**
     * Redis Key 过期机制执行器
     */
    private final ScheduledExecutorService expireExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("DubboRegistryExpireTimer", true));

    /**
     * Redis Key 过期机制任务的Future
     */
    private final ScheduledFuture<?> expireFuture;

    /**
     * Redis 根节点
     */
    private final String root;

    /**
     * JedisPool 集合
     * key: ip:port
     */
    private final Map<String, JedisPool> jedisPools = new ConcurrentHashMap<String, JedisPool>();
    /**
     * 通知器集合，用于Redis Publish/Subscribe机制中的订阅，实时监听数据的变化
     * key: Root + Service,例如： /dubbo/com.alibaba.dubbo.demo.DemoService
     */
    private final ConcurrentMap<String, Notifier> notifiers = new ConcurrentHashMap<String, Notifier>();

    /**
     * 重连周期，单位： 毫秒。用于订阅发生Redis连接异常时，Notifier sleep，等待重连上
     */
    private final int reconnectPeriod;

    /**
     * 过期周期，单位：毫秒
     */
    private final int expirePeriod;

    /**
     * 是否监控中心
     * 用于判断脏数据，脏数据由监控中心删除{@link #clean(Jedis)}
     */
    private volatile boolean admin = false;

    /**
     * 是否复制模式，可通过 <dubbo:registry cluster="replicate"/>设置redis集群策略，缺省是failover
     * 1 failover: 只写入和读取任意一台，失败时重试另一台，需要服务端自行配置数据同步
     * 2 replicate: 在客户端同时写入所有服务器，只读取单台，服务器端不需要同步，注册中心集群增大，性能压力也会增大
     */
    private boolean replicate;

    public RedisRegistry(URL url) {
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        // 创建 GenericObjectPoolConfig 对象
        GenericObjectPoolConfig config = new GenericObjectPoolConfig();
        config.setTestOnBorrow(url.getParameter("test.on.borrow", true));
        config.setTestOnReturn(url.getParameter("test.on.return", false));
        config.setTestWhileIdle(url.getParameter("test.while.idle", false));
        if (url.getParameter("max.idle", 0) > 0) {
            config.setMaxIdle(url.getParameter("max.idle", 0));
        }
        if (url.getParameter("min.idle", 0) > 0) {
            config.setMinIdle(url.getParameter("min.idle", 0));
        }
        if (url.getParameter("max.active", 0) > 0) {
            config.setMaxTotal(url.getParameter("max.active", 0));
        }
        if (url.getParameter("max.total", 0) > 0) {
            config.setMaxTotal(url.getParameter("max.total", 0));
        }
        if (url.getParameter("max.wait", url.getParameter("timeout", 0)) > 0) {
            config.setMaxWaitMillis(url.getParameter("max.wait", url.getParameter("timeout", 0)));
        }
        if (url.getParameter("num.tests.per.eviction.run", 0) > 0) {
            config.setNumTestsPerEvictionRun(url.getParameter("num.tests.per.eviction.run", 0));
        }
        if (url.getParameter("time.between.eviction.runs.millis", 0) > 0) {
            config.setTimeBetweenEvictionRunsMillis(url.getParameter("time.between.eviction.runs.millis", 0));
        }
        if (url.getParameter("min.evictable.idle.time.millis", 0) > 0) {
            config.setMinEvictableIdleTimeMillis(url.getParameter("min.evictable.idle.time.millis", 0));
        }

        // 是否复制模式
        String cluster = url.getParameter("cluster", "failover");
        if (!"failover".equals(cluster) && !"replicate".equals(cluster)) {
            throw new IllegalArgumentException("Unsupported redis cluster: " + cluster + ". The redis cluster only supported failover or replicate.");
        }
        replicate = "replicate".equals(cluster);

        // 解析
        List<String> addresses = new ArrayList<String>();
        addresses.add(url.getAddress());
        // ULR中设置了从库地址
        String[] backups = url.getParameter(Constants.BACKUP_KEY, new String[0]);
        if (backups != null && backups.length > 0) {
            addresses.addAll(Arrays.asList(backups));
        }

        // 创建JedisPool对象
        for (String address : addresses) {
            int i = address.indexOf(':');
            String host;
            int port;
            if (i > 0) {
                host = address.substring(0, i);
                port = Integer.parseInt(address.substring(i + 1));
            } else {
                host = address;
                port = DEFAULT_REDIS_PORT;
            }
            this.jedisPools.put(address, new JedisPool(config, host, port,
                    url.getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT), StringUtils.isEmpty(url.getPassword()) ? null : url.getPassword(),
                    url.getParameter("db.index", 0)));
        }

        // 解析重连周期
        this.reconnectPeriod = url.getParameter(Constants.REGISTRY_RECONNECT_PERIOD_KEY, Constants.DEFAULT_REGISTRY_RECONNECT_PERIOD);
        // 获得Redis 根节点
        String group = url.getParameter(Constants.GROUP_KEY, DEFAULT_ROOT);
        if (!group.startsWith(Constants.PATH_SEPARATOR)) {
            group = Constants.PATH_SEPARATOR + group;
        }
        if (!group.endsWith(Constants.PATH_SEPARATOR)) {
            group = group + Constants.PATH_SEPARATOR;
        }
        this.root = group;

        // 解析过期周期
        this.expirePeriod = url.getParameter(Constants.SESSION_TIMEOUT_KEY, Constants.DEFAULT_SESSION_TIMEOUT);

        // 创建实现Redis Key 过期机制的任务
        this.expireFuture = expireExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    // 延时过期时间
                    deferExpired();
                } catch (Throwable t) { // Defensive fault tolerance
                    logger.error("Unexpected exception occur at defer expire time, cause: " + t.getMessage(), t);
                }
            }
        }, expirePeriod / 2, expirePeriod / 2, TimeUnit.MILLISECONDS);
    }

    /**
     * 被Key过期机制执行器expireExecutor定时调用，用来延时过期时间。整体逻辑类似 #doRegister()方法
     */
    private void deferExpired() {
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                Jedis jedis = jedisPool.getResource();
                try {
                    // 循环已注册的URL集合
                    for (URL url : new HashSet<URL>(getRegistered())) {
                        // 是否是动态节点，只有动态节点需要延长过期时间
                        if (url.getParameter(Constants.DYNAMIC_KEY, true)) {
                            // 获得分类路径
                            String key = toCategoryPath(url);
                            /**
                             * 1  写入Redis Map中，过期时间被更新。
                             * 2 注意，如果过期时间更新的时候返回值为1，说明该Redis 中Map的建对应的值不存在（例如：多写Redis节点时，某个节点写入失败），该Redis就要发布注册事件
                             */
                            if (jedis.hset(key, url.toFullString(), String.valueOf(System.currentTimeMillis() + expirePeriod)) == 1) {
                                // 发布 `register` 事件
                                jedis.publish(key, Constants.REGISTER);
                            }
                        }
                    }
                    // 如果是监控中心（admin = true），就负责删除过期脏数据
                    if (admin) {
                        clean(jedis);
                    }
                    // 如果服务器端已同步数据，只需写入单台机器
                    if (!replicate) {
                        break;//  If the server side has synchronized data, just write a single machine
                    }
                } finally {
                    jedis.close();
                }
            } catch (Throwable t) {
                logger.warn("Failed to write provider heartbeat to redis registry. registry: " + entry.getKey() + ", cause: " + t.getMessage(), t);
            }
        }
    }

    /**
     * 监控中心负责清理过期脏数据
     *
     * @param jedis
     */
    private void clean(Jedis jedis) {
        // 获得所有类目
        Set<String> keys = jedis.keys(root + Constants.ANY_VALUE);
        if (keys != null && !keys.isEmpty()) {
            for (String key : keys) {
                // 获得类目下Map,key->URL,value->过期时间
                Map<String, String> values = jedis.hgetAll(key);
                if (values != null && values.size() > 0) {
                    boolean delete = false;
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        // 获取URL
                        URL url = URL.valueOf(entry.getKey());
                        // 动态节点
                        if (url.getParameter(Constants.DYNAMIC_KEY, true)) {
                            // 获取URL对应的过期时间
                            long expire = Long.parseLong(entry.getValue());
                            // 已经过期
                            if (expire < now) {
                                jedis.hdel(key, entry.getKey());
                                delete = true;
                                if (logger.isWarnEnabled()) {
                                    logger.warn("Delete expired key: " + key + " -> value: " + entry.getKey() + ", expire: " + new Date(expire) + ", now: " + new Date(now));
                                }
                            }
                        }
                    }
                    // 若删除成功，发布 `unregister`事件
                    if (delete) {
                        jedis.publish(key, Constants.UNREGISTER);
                    }
                }
            }
        }
    }

    @Override
    public boolean isAvailable() {
        for (JedisPool jedisPool : jedisPools.values()) {
            try {
                Jedis jedis = jedisPool.getResource();
                try {
                    // 至少一个Redis 节点可用
                    if (jedis.isConnected()) {
                        return true; // At least one single machine is available.
                    }
                } finally {
                    jedis.close();
                }
            } catch (Throwable t) {
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        // 父类关闭
        super.destroy();
        try {
            // 关闭定时任务
            expireFuture.cancel(true);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        try {
            // 关闭通知器
            for (Notifier notifier : notifiers.values()) {
                notifier.shutdown();
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        // 关闭连接池
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                jedisPool.destroy();
            } catch (Throwable t) {
                logger.warn("Failed to destroy the redis registry client. registry: " + entry.getKey() + ", cause: " + t.getMessage(), t);
            }
        }
        ExecutorUtil.gracefulShutdown(expireExecutor, expirePeriod);
    }

    @Override
    public void doRegister(URL url) {
        // 获得分类路径作为key
        String key = toCategoryPath(url);
        // 获得URL字符串作为Value
        String value = url.toFullString();
        // 计算过期时间，这会作为Redis Map的值
        String expire = String.valueOf(System.currentTimeMillis() + expirePeriod);
        boolean success = false;
        RpcException exception = null;
        // 向Redis注册
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                Jedis jedis = jedisPool.getResource();
                try {
                    // 写入Redis hash 中，注意，过期时间是作为Map的值
                    jedis.hset(key, value, expire);
                    // 发布Redis 注册事件。 key为频道， Constants.REGISTER->register为事件。这样订阅该频道的服务消费者和监控中心，就会实时从Redis读取该服务的最新数据。
                    jedis.publish(key, Constants.REGISTER);
                    success = true;
                    // 如果非replicate模式，意味着Redis服务器端已经同步数据，只需要写入单台机器，因此结束循环。否则，就继续循环，向所有的Redis写入
                    if (!replicate) {
                        break; //  If the server side has synchronized data, just write a single machine
                    }
                } finally {
                    jedis.close();
                }
            } catch (Throwable t) {
                exception = new RpcException("Failed to register service to redis registry. registry: " + entry.getKey() + ", service: " + url + ", cause: " + t.getMessage(), t);
            }
        }
        // 处理异常
        if (exception != null) {
            // 虽然发生异常，但是结果是成功的，打印告警日志。这里也适用于多次重试某个操作
            if (success) {
                logger.warn(exception.getMessage(), exception);
                // 失败
            } else {
                throw exception;
            }
        }
    }

    /**
     * 当服务消费者或服务提供者关闭时，会调用该方法，取消注册。该方法中，会删除对应Map中的建 + 发布unregister事件，从而实时通知订阅者们。
     * 因此正常情况下，就无需监控中心做脏数据删除的工作。这个方法逻辑和doRegister方法相反
     *
     * @param url
     */
    @Override
    public void doUnregister(URL url) {
        // 获得分类路径作为key
        String key = toCategoryPath(url);
        // 获得URL字符串作为Value
        String value = url.toFullString();
        RpcException exception = null;
        boolean success = false;
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                Jedis jedis = jedisPool.getResource();
                try {
                    // 删除 Redis Map 建
                    jedis.hdel(key, value);
                    // 发布Redis 取消注册事件 key为频道 ， Constants.UNREGISTER->unregister 为事件
                    jedis.publish(key, Constants.UNREGISTER);
                    success = true;
                    // 如果非replicate模式，意味着Redis服务器端已经同步数据，只需要写入单台机器，因此结束循环。否则，就继续循环，向所有的Redis写入
                    if (!replicate) {
                        break; //  If the server side has synchronized data, just write a single machine
                    }
                } finally {
                    jedis.close();
                }
            } catch (Throwable t) {
                exception = new RpcException("Failed to unregister service to redis registry. registry: " + entry.getKey() + ", service: " + url + ", cause: " + t.getMessage(), t);
            }
        }
        if (exception != null) {
            if (success) {
                logger.warn(exception.getMessage(), exception);
            } else {
                throw exception;
            }
        }
    }

    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        // 获得服务路径，如： /dubbo/com.alibaba.dubbo.demo.DemoService
        String service = toServicePath(url);
        // 获得服务路径对应的通知器 Notifier 对象
        Notifier notifier = notifiers.get(service);
        // 不存在对应的通知器，则创建Notifier对象
        if (notifier == null) {
            // 创建服务路径对应的通知器Notifier对象
            Notifier newNotifier = new Notifier(service);
            notifiers.putIfAbsent(service, newNotifier);
            notifier = notifiers.get(service);
            // 保证并发的情况下，有且仅有一个启动
            if (notifier == newNotifier) {
                //todo  订阅时，启动监听数据的变化（订阅了频道，有事件发布就会收到，然后就行进行通知处理） 【依赖Redis的Subscribe收到消息后进行通知】
                notifier.start();
            }
        }
        boolean success = false;
        RpcException exception = null;

        // todo 循环 jedisPools,仅从一个Redis获取数据，然后进行通知，直到成功【这里为什么只是一个？是因为在这之前Redis数据已经同步过了】，这里直接对订阅URL 进行通知
        for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
            JedisPool jedisPool = entry.getValue();
            try {
                Jedis jedis = jedisPool.getResource();
                try {
                    // 处理所有 Service 层的发起订阅，例如监听中心的订阅
                    if (service.endsWith(Constants.ANY_VALUE)) {
                        // 标记admin = true,因为只有监控中心才清理脏数据
                        admin = true;
                        // 获得分类层集合，例如：`/dubbo/*`
                        Set<String> keys = jedis.keys(service);
                        if (keys != null && !keys.isEmpty()) {
                            // 按照服务聚合URL集合
                            Map<String, Set<String>> serviceKeys = new HashMap<String, Set<String>>();
                            for (String key : keys) {
                                String serviceKey = toServicePath(key);
                                Set<String> sk = serviceKeys.get(serviceKey);
                                if (sk == null) {
                                    sk = new HashSet<String>();
                                    serviceKeys.put(serviceKey, sk);
                                }
                                sk.add(key);
                            }
                            // 循环serviceKeys，按照每个Service 层发起通知
                            for (Set<String> sk : serviceKeys.values()) {
                                doNotify(jedis, sk, url, Arrays.asList(listener));
                            }
                        }
                        // 处理指定Service 层的发起通知，适用服务提供者和服务消费者
                    } else {
                        /**
                         * 1 调用Jedis#keys(pattern)方法，获得所有类目，例如： /dubbo/com.alibaba.dubbo.demo.DemoService/*
                         * 2 通知监听器
                         */

                        doNotify(jedis, jedis.keys(service + Constants.PATH_SEPARATOR + Constants.ANY_VALUE), url, Arrays.asList(listener));
                    }
                    // 标记成功
                    success = true;
                    // Just read one server's data
                    break;
                } finally {
                    jedis.close();
                }
            } catch (Throwable t) { // Try the next server
                exception = new RpcException("Failed to subscribe service from redis registry. registry: " + entry.getKey() + ", service: " + url + ", cause: " + t.getMessage(), t);
            }
        }
        // 处理异常
        if (exception != null) {
            if (success) {
                logger.warn(exception.getMessage(), exception);
            } else {
                throw exception;
            }
        }
    }

    /**
     * 目前没有实现。不过这里应该增加取消向Redis的订阅。在ZookeeperRegistry的该方法中，是移除了对应的监听器
     *
     * @param url
     * @param listener
     */
    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
    }

    /**
     * @param jedis
     * @param key   分类数组，例如： /dubbo/com.alibaba.dubbo.demo.DemoService/providers
     */
    private void doNotify(Jedis jedis, String key) {
        // 调用getSubscribed()方法，获得所有 订阅 URL 的监听器集合
        for (Map.Entry<URL, Set<NotifyListener>> entry : new HashMap<URL, Set<NotifyListener>>(getSubscribed()).entrySet()) {
            doNotify(jedis, Arrays.asList(key), entry.getKey(), new HashSet<NotifyListener>(entry.getValue()));
        }
    }

    /**
     * @param jedis     Jedis
     * @param keys      分类数组 ，如 /dubbo/com.alibaba.dubbo.demo.DemoService/providers
     * @param url       订阅URL
     * @param listeners 订阅URL对应的监听器集合
     */
    private void doNotify(Jedis jedis, Collection<String> keys, URL url, Collection<NotifyListener> listeners) {
        if (keys == null || keys.isEmpty()
                || listeners == null || listeners.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<URL> result = new ArrayList<URL>();
        /**
         * 获得分类数组，不同的角色关注不同的分类数据【zookeeper也是如此】
         * 1 服务消费者，关注providers,configurations.routes
         * 3 监控中心关注所有
         */
        List<String> categories = Arrays.asList(url.getParameter(Constants.CATEGORY_KEY, new String[0]));
        // 服务接口
        String consumerService = url.getServiceInterface();
        // 循环分类层，如： /dubbo/com.alibaba.dubbo.demo.DemoService/providers
        for (String key : keys) {
            // 若服务不匹配，返回
            if (!Constants.ANY_VALUE.equals(consumerService)) {
                String prvoiderService = toServiceName(key);
                if (!prvoiderService.equals(consumerService)) {
                    continue;
                }
            }
            // 如果订阅的不包含该分类，返回
            String category = toCategoryName(key);
            if (!categories.contains(Constants.ANY_VALUE) && !categories.contains(category)) {
                continue;
            }
            List<URL> urls = new ArrayList<URL>();
            // 获得分类下所有URL数组
            Map<String, String> values = jedis.hgetAll(key);
            if (values != null && values.size() > 0) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    URL u = URL.valueOf(entry.getKey());
                    // 过滤掉已过期的动态节点 [动态节点才可能会变化，把动态节点收集起来，去和原来的节点对比，看是否有变化，有变化就需要做些操作，如 服务重新暴露]
                    if (!u.getParameter(Constants.DYNAMIC_KEY, true) || Long.parseLong(entry.getValue()) >= now) {
                        if (UrlUtils.isMatch(url, u)) {
                            urls.add(u);
                        }
                    }
                }
            }
            // 若不存在匹配，则创建 `empty://` 的 URL返回，用于清空该服务的该分类。
            if (urls.isEmpty()) {
                urls.add(url.setProtocol(Constants.EMPTY_PROTOCOL)
                        .setAddress(Constants.ANYHOST_VALUE)
                        .setPath(toServiceName(key))
                        .addParameter(Constants.CATEGORY_KEY, category));
            }
            result.addAll(urls);
            if (logger.isInfoEnabled()) {
                logger.info("redis notify: " + key + " = " + urls);
            }
        }
        if (result == null || result.isEmpty()) {
            return;
        }
        // 回调NotifyListener
        for (NotifyListener listener : listeners) {
            notify(url, listener, result);
        }
    }

    /**
     * 获得服务名，从服务路径上
     * <p>
     * Service
     *
     * @param categoryPath 服务路径
     * @return 服务名
     */
    private String toServiceName(String categoryPath) {
        String servicePath = toServicePath(categoryPath);
        return servicePath.startsWith(root) ? servicePath.substring(root.length()) : servicePath;
    }

    /**
     * 获得分类名，从分类路径上
     * <p>
     * Type
     *
     * @param categoryPath
     * @return
     */
    private String toCategoryName(String categoryPath) {
        int i = categoryPath.lastIndexOf(Constants.PATH_SEPARATOR);
        return i > 0 ? categoryPath.substring(i + 1) : categoryPath;
    }

    /**
     * 获取服务路径，主要截取掉多余的部分
     * Root + Service
     *
     * @param categoryPath 分类路径
     * @return 服务路径
     */
    private String toServicePath(String categoryPath) {
        int i;
        if (categoryPath.startsWith(root)) {
            i = categoryPath.indexOf(Constants.PATH_SEPARATOR, root.length());
        } else {
            i = categoryPath.indexOf(Constants.PATH_SEPARATOR);
        }
        return i > 0 ? categoryPath.substring(0, i) : categoryPath;
    }

    /**
     * 获得服务路径
     * Root + Type
     *
     * @param url URL
     * @return 服务路径
     */
    private String toServicePath(URL url) {
        return root + url.getServiceInterface();
    }

    private String toCategoryPath(URL url) {
        return toServicePath(url) + Constants.PATH_SEPARATOR + url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
    }

    /**
     * RedisRegistry 的内部类，继承 redis.clients.jedis.JedisPubSub 抽象类，它是个通知订阅实现类
     */
    private class NotifySub extends JedisPubSub {

        private final JedisPool jedisPool;

        public NotifySub(JedisPool jedisPool) {
            this.jedisPool = jedisPool;
        }


        @Override
        public void onMessage(String key, String msg) {
            if (logger.isInfoEnabled()) {
                logger.info("redis event: " + key + " = " + msg);
            }
            // 收到register/unregister事件，调用#doNotify方法，通知监听器，数据变化，从而实现实时更新
            if (msg.equals(Constants.REGISTER)
                    || msg.equals(Constants.UNREGISTER)) {
                try {
                    Jedis jedis = jedisPool.getResource();
                    try {
                        // 进行通知
                        doNotify(jedis, key);
                    } finally {
                        jedis.close();
                    }
                } catch (Throwable t) { // TODO Notification failure does not restore mechanism guarantee
                    logger.error(t.getMessage(), t);
                }
            }
        }

        @Override
        public void onPMessage(String pattern, String key, String msg) {
            onMessage(key, msg);
        }

        @Override
        public void onSubscribe(String key, int num) {
        }

        @Override
        public void onPSubscribe(String pattern, int num) {
        }

        @Override
        public void onUnsubscribe(String key, int num) {
        }

        @Override
        public void onPUnsubscribe(String pattern, int num) {
        }

    }

    /**
     * 通知器类，用于Redis Publish/Subscribe机制中的订阅，实时监听数据的变化
     */
    private class Notifier extends Thread {

        /**
         * 服务名 Root + Service
         */
        private final String service;
        /**
         * 需要忽略连接的次数
         */
        private final AtomicInteger connectSkip = new AtomicInteger();
        /**
         * 已经忽略连接的次数
         */
        private final AtomicInteger connectSkiped = new AtomicInteger();
        /**
         * 随机对象
         */
        private final Random random = new Random();
        /**
         * Jedis
         */
        private volatile Jedis jedis;
        /**
         * 是否首次
         */
        private volatile boolean first = true;
        /**
         * 是否运行中
         */
        private volatile boolean running = true;
        /**
         * 连接次数随机数
         */
        private volatile int connectRandom;

        public Notifier(String service) {
            super.setDaemon(true);
            super.setName("DubboRedisSubscribe");
            this.service = service;
        }

        /**
         * 重置忽略连接的信息
         */
        private void resetSkip() {
            // 重置需要忽略连接的次数
            connectSkip.set(0);
            // 重置已忽略次数和随机数
            connectSkiped.set(0);
            connectRandom = 0;
        }

        /**
         * 判断是否忽略本次对Redis的连接
         * <p>
         * 思路是，连接失败的次数越多，每一轮加大需要忽略的总次数，并且带有一定的随机性。
         *
         * @return
         */
        private boolean isSkip() {
            // 获得需要忽略连接的总次数，如果超过10，则加上一个10以内的随机数
            int skip = connectSkip.get(); // Growth of skipping times
            if (skip >= 10) { // If the number of skipping times increases by more than 10, take the random number
                if (connectRandom == 0) {
                    connectRandom = random.nextInt(10);
                }
                skip = 10 + connectRandom;
            }
            // 自增忽略次数，若忽略次数不够，则继续忽略
            if (connectSkiped.getAndIncrement() < skip) { // Check the number of skipping times
                return true;
            }
            // 增加需要忽略的次数【也就是说，下一轮不考虑随机数，会多一次】
            connectSkip.incrementAndGet();
            // 重置已忽略次数和随机数
            connectSkiped.set(0);
            connectRandom = 0;
            return false;
        }


           @Override
        public void run() {
            while (running) {
                try {
                    // 是否跳过本次Redis连接 【todo 整个看下来，即使跳过，也没有执行类似休眠的逻辑，这样的话即使跳过了，但是也会很快向Redis发起订阅】
                    if (!isSkip()) {
                        try {
                            // 循环连接池
                            for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
                                JedisPool jedisPool = entry.getValue();
                                try {
                                    jedis = jedisPool.getResource();
                                    try {
                                        // 监控中心
                                        if (service.endsWith(Constants.ANY_VALUE)) {
                                            if (!first) {
                                                first = false;
                                                Set<String> keys = jedis.keys(service);
                                                if (keys != null && !keys.isEmpty()) {
                                                    for (String s : keys) {
                                                        doNotify(jedis, s);
                                                    }
                                                }
                                                resetSkip();
                                            }
                                            // 批订阅
                                            jedis.psubscribe(new NotifySub(jedisPool), service); // blocking
                                            // 服务提供者或者消费者
                                        } else {
                                            if (!first) {
                                                first = false;
                                                doNotify(jedis, service);
                                                resetSkip();
                                            }
                                            // 批订阅
                                            jedis.psubscribe(new NotifySub(jedisPool), service + Constants.PATH_SEPARATOR + Constants.ANY_VALUE); // blocking
                                        }
                                        break;
                                    } finally {
                                        jedis.close();
                                    }
                                } catch (Throwable t) { // Retry another server
                                    logger.warn("Failed to subscribe service from redis registry. registry: " + entry.getKey() + ", cause: " + t.getMessage(), t);
                                    // If you only have a single redis, you need to take a rest to avoid overtaking a lot of CPU resources
                                    sleep(reconnectPeriod);
                                }
                            }
                        } catch (Throwable t) {
                            logger.error(t.getMessage(), t);
                            sleep(reconnectPeriod);
                        }
                    }
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        }


     /* 改版后测方法
        @Override
        public void run() {
            while (running) {
                try {
                    // 是否跳过本次Redis连接 【todo 整个看下来，即使跳过，也没有执行类似休眠的逻辑，这样的话即使跳过了，但是也会很快向Redis发起订阅】
                    if (!isSkip()) {
                        try {
                            // 循环连接池
                            for (Map.Entry<String, JedisPool> entry : jedisPools.entrySet()) {
                                JedisPool jedisPool = entry.getValue();
                                try {
                                    jedis = jedisPool.getResource();
                                    try {
                                        // 监控中心
                                        if (service.endsWith(Constants.ANY_VALUE)) {
                                            // todo 起初时 first =true，那么这块代码永远无法执行到。这块代码的意图应该是Redis 订阅发生了异常【可能Redis连接断开了】需要重新发起订阅，并且需要**重新**从 Redis 中获取到最新的数据。
                                            if (!first) {
                                                first = true;
                                                Set<String> keys = jedis.keys(service);
                                                if (keys != null && !keys.isEmpty()) {
                                                    for (String s : keys) {
                                                        doNotify(jedis, s);
                                                    }
                                                }
                                                resetSkip();
                                            }
                                            // 批订阅(阻塞的)，收到消息后交给NotifySub处理
                                            jedis.psubscribe(new NotifySub(jedisPool), service); // blocking
                                            // 服务提供者或者消费者
                                        } else {
                                            if (!first) {
                                                first = true;
                                                doNotify(jedis, service);
                                                resetSkip();
                                            }
                                            // 批订阅 (阻塞的)，收到消息后交给NotifySub处理
                                            jedis.psubscribe(new NotifySub(jedisPool), service + Constants.PATH_SEPARATOR + Constants.ANY_VALUE); // blocking
                                        }
                                        break;
                                    } finally {
                                        jedis.close();
                                    }
                                } catch (Throwable t) { // Retry another server
                                    first = false;
                                    logger.warn("Failed to subscribe service from redis registry. registry: " + entry.getKey() + ", cause: " + t.getMessage(), t);
                                    // If you only have a single redis, you need to take a rest to avoid overtaking a lot of CPU resources
                                    // 发生异常，说明 Redis 连接可能断开了。因此，调用 `#sleep(millis)` 方法，等待 Redis 重连成功。通过这样的方式，避免执行，占用大量的 CPU 资源。
                                    sleep(reconnectPeriod);
                                }
                            }
                        } catch (Throwable t) {
                            logger.error(t.getMessage(), t);
                            sleep(reconnectPeriod);
                        }
                    }
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        } */

        public void shutdown() {
            try {
                // 停止运行
                running = false;
                // Jedis断开连接
                jedis.disconnect();
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

    }

}
