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
package com.alibaba.dubbo.registry.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AbstractRegistry. (SPI, Prototype, ThreadSafe)
 * 1 实现Registry 接口
 * 2 实现了以下方法：
 * - 通用的注册、订阅、查询、通知等方法
 * - 持久化注册数据到文件，以properties格式存储。用于，当重启时无法从注册中心加载服务提供者列表信息时，就可以从该文件中读取
 */
public abstract class AbstractRegistry implements Registry {

    // Log output
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    // URL地址分割符，用于文件缓存中，服务提供者URL分隔
    private static final char URL_SEPARATOR = ' ';
    // URL地址分隔正则表达式，用于解析文件缓存中服务提供者URL列表
    private static final String URL_SPLIT = "\\s+";

    /**
     * 本地磁盘缓存
     * 1 其中特殊的 key值 .registries 记录注册中心列表
     * 2 其他的均为{@link #notified} 服务提供者列表
     * 3 数据流向： 启动时，从file读取数据到 properties中。注册中心数据发生变更时，通知到Registry后，修改properties对应的值，并写入file
     * 4 数据的键-值对
     * （1）大多数情况下，键为`服务消费者的URL`【此消费者非消费者】的服务键 {@link URL#getServiceKey()},值为服务提供者列表/路由规则列表/配置规则列表
     * （2）特殊情况下， .registries
     * （3）值会存在为列表的情况，使用空格  URL_SEPARATOR
     */
    // Local disk cache, where the special key value.registies records the list of registry centers, and the others are the list of notified service providers
    private final Properties properties = new Properties();

    /**
     * 注册中心缓存写入执行器（线程数为1）
     */
    // File cache timing writing
    private final ExecutorService registryCacheExecutor = Executors.newFixedThreadPool(1, new NamedThreadFactory("DubboSaveRegistryCache", true));

    /**
     * properties发生变更时，是否同步写入文件
     */
    // Is it synchronized to save the file
    private final boolean syncSaveFile;
    /**
     * 数据版本号 {@link #properties}
     */
    private final AtomicLong lastCacheChanged = new AtomicLong();
    /**
     * 已注册 URL 集合。注意，注册的URL不仅仅可以是服务提供者的，也可以是服务消费者的
     */
    private final Set<URL> registered = new ConcurrentHashSet<URL>();
    /**
     * 订阅 URL 的监听器集合
     * key : `消费者的URL` [此消费者非消费者] ,例如： 消费者的URL
     */
    private final ConcurrentMap<URL, Set<NotifyListener>> subscribed = new ConcurrentHashMap<URL, Set<NotifyListener>>();
    /**
     * 被通知的URL集合
     * key1: 订阅的URL,例如消费者的URL，和 {@link #subscribed} 的键一致
     * key2: 分类，例如： providers,consumers,routes,configurators。【实际上无consumers,因为消费者不会去订阅另外的消费者的列表】，在{@link Constants}中，以 "_CATEGORY"结尾
     * ---
     * 从数据上看，和properties比较相似，但有两点差异：
     * 1 数据格式上，notified 根据分类做了聚合
     * 2 不从file 中读取，都是从注册中心读取的数据
     */
    private final ConcurrentMap<URL, Map<String, List<URL>>> notified = new ConcurrentHashMap<URL, Map<String, List<URL>>>();
    /**
     * 注册中心 URL
     */
    private URL registryUrl;

    /**
     * 本地磁盘缓存文件，缓存注册中心的数据
     */
    private File file;

    public AbstractRegistry(URL url) {
        setUrl(url);

        /**
         * 可以指定注册中心磁盘缓存文件，配置方式：
         * 1 使用file属性指定  <dubbo:registry address="xxx" file="/opt/xxx"/>
         * 2 使用save.file属性指定 <dubbo:registry address="xxx" save.file="/opt/xxx"/>
         * 3 不显示指定的话，使用默认值：System.getProperty("user.home") + "/.dubbo/dubbo-registry-" + 应用名 + "-" + url.getAddress() + ".cache"
         */
        // Start file save timer
        syncSaveFile = url.getParameter(Constants.REGISTRY_FILESAVE_SYNC_KEY, false);
        // 获得 file
        String filename = url.getParameter(Constants.FILE_KEY, System.getProperty("user.home") + "/.dubbo/dubbo-registry-" + url.getParameter(Constants.APPLICATION_KEY) + "-" + url.getAddress() + ".cache");
        File file = null;
        if (ConfigUtils.isNotEmpty(filename)) {
            file = new File(filename);
            if (!file.exists() && file.getParentFile() != null && !file.getParentFile().exists()) {
                if (!file.getParentFile().mkdirs()) {
                    throw new IllegalArgumentException("Invalid registry store file " + file + ", cause: Failed to create directory " + file.getParentFile() + "!");
                }
            }
        }
        this.file = file;
        // 加载本地磁盘缓存文件到内存缓存，即 properties.load(in)，到properties属性中
        loadProperties();
        // 通知监听器，URL 变化结果 todo 为什么构造方法要通知，连监听器都没有注册，即 subscribed 里面还没有值
        notify(url.getBackupUrls());
    }

    protected static List<URL> filterEmpty(URL url, List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            List<URL> result = new ArrayList<URL>(1);
            result.add(url.setProtocol(Constants.EMPTY_PROTOCOL));
            return result;
        }
        return urls;
    }

    @Override
    public URL getUrl() {
        return registryUrl;
    }

    protected void setUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("registry url == null");
        }
        this.registryUrl = url;
    }

    public Set<URL> getRegistered() {
        return registered;
    }

    public Map<URL, Set<NotifyListener>> getSubscribed() {
        return subscribed;
    }

    public Map<URL, Map<String, List<URL>>> getNotified() {
        return notified;
    }

    public File getCacheFile() {
        return file;
    }

    public Properties getCacheProperties() {
        return properties;
    }

    public AtomicLong getLastCacheChanged() {
        return lastCacheChanged;
    }

    /**
     * @param version
     */
    public void doSaveProperties(long version) {
        /**
         * CAS判断：在saveProperties(URL url)方法中执行了long version = lastCacheChanged.incrementAndGet();
         * 这里进行if (version < lastCacheChanged.get())判断，如果满足这个条件，说明当前线程在进行doSaveProperties(long version)时，
         * 已经有其他线程执行了saveProperties(URL url)，马上就要执行doSaveProperties(long version)，所以当前线程放弃操作，让后边的这个线程来做保存操作。
         */
        if (version < lastCacheChanged.get()) {
            return;
        }
        if (file == null) {
            return;
        }
        // Save
        try {
            File lockfile = new File(file.getAbsolutePath() + ".lock");
            if (!lockfile.exists()) {
                lockfile.createNewFile();
            }
            RandomAccessFile raf = new RandomAccessFile(lockfile, "rw");
            try {
                FileChannel channel = raf.getChannel();
                try {
                    FileLock lock = channel.tryLock();
                    if (lock == null) {
                        throw new IOException("Can not lock the registry cache file " + file.getAbsolutePath() + ", ignore and retry later, maybe multi java process use the file, please config: dubbo.registry.file=xxx.properties");
                    }
                    // Save
                    try {
                        if (!file.exists()) {
                            file.createNewFile();
                        }
                        FileOutputStream outputFile = new FileOutputStream(file);
                        try {
                            properties.store(outputFile, "Dubbo Registry Cache");
                        } finally {
                            outputFile.close();
                        }
                    } finally {
                        lock.release();
                    }
                } finally {
                    channel.close();
                }
            } finally {
                raf.close();
            }
        } catch (Throwable e) {
            if (version < lastCacheChanged.get()) {
                return;
            } else {
                registryCacheExecutor.execute(new SaveProperties(lastCacheChanged.incrementAndGet()));
            }
            logger.warn("Failed to save registry store file, cause: " + e.getMessage(), e);
        }
    }

    /**
     * 加载本地磁盘缓存文件到内存缓存
     */
    private void loadProperties() {
        if (file != null && file.exists()) {
            InputStream in = null;
            try {
                in = new FileInputStream(file);
                properties.load(in);
                if (logger.isInfoEnabled()) {
                    logger.info("Load registry store file " + file + ", data: " + properties);
                }
            } catch (Throwable e) {
                logger.warn("Failed to load registry store file " + file, e);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        }
    }


    public List<URL> getCacheUrls(URL url) {
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            if (key != null && key.length() > 0 && key.equals(url.getServiceKey())
                    && (Character.isLetter(key.charAt(0)) || key.charAt(0) == '_')
                    && value != null && value.length() > 0) {
                String[] arr = value.trim().split(URL_SPLIT);
                List<URL> urls = new ArrayList<URL>();
                for (String u : arr) {
                    urls.add(URL.valueOf(u));
                }
                return urls;
            }
        }
        return null;
    }


    @Override
    public List<URL> lookup(URL url) {
        List<URL> result = new ArrayList<URL>();
        Map<String, List<URL>> notifiedUrls = getNotified().get(url);
        if (notifiedUrls != null && notifiedUrls.size() > 0) {
            for (List<URL> urls : notifiedUrls.values()) {
                for (URL u : urls) {
                    if (!Constants.EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        result.add(u);
                    }
                }
            }
        } else {
            final AtomicReference<List<URL>> reference = new AtomicReference<List<URL>>();
            NotifyListener listener = new NotifyListener() {
                @Override
                public void notify(List<URL> urls) {
                    reference.set(urls);
                }
            };
            subscribe(url, listener); // Subscribe logic guarantees the first notify to return
            List<URL> urls = reference.get();
            if (urls != null && !urls.isEmpty()) {
                for (URL u : urls) {
                    if (!Constants.EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        result.add(u);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 从这里看，并没有向注册中心发起注册，仅仅是添加到 registered 缓存中，进行状态的维护。实际上，真正的实现是在其子类 FailbackRegistry类 中
     *
     * @param url Registration information , is not allowed to be empty, e.g: dubbo://10.20.153.10/com.alibaba.foo.BarService?version=1.0.0&application=kylin
     */
    @Override
    public void register(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("register url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Register: " + url);
        }
        registered.add(url);
    }

    /**
     * 先从 registered 缓存中移除，再由子类 FailbackRegistry 真正取消注册
     *
     * @param url Registration information , is not allowed to be empty, e.g: dubbo://10.20.153.10/com.alibaba.foo.BarService?version=1.0.0&application=kylin
     */
    @Override
    public void unregister(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("unregister url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unregister: " + url);
        }
        registered.remove(url);
    }

    /**
     * 先缓存到 subscribed 中，再通过子类 FailbackRegistry 具体执行订阅逻辑
     *
     * @param url      订阅条件，不允许为空，如：consumer://10.10.10.10/com.alibaba.foo.BarService?version=1.0.0&application=kylin
     * @param listener 变更事件监听器，不允许为空
     */
    @Override
    public void subscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("subscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("subscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Subscribe: " + url);
        }
        /**
         * 1 从ConcurrentMap<URL, Set<NotifyListener>> subscribed中获取key为url的集合Set<NotifyListener>
         * 2 如果该集合存在，直接将当前的NotifyListener实例存入该集合,如果集合不存在，先创建，之后放入subscribed中，并将当前的NotifyListener实例存入刚刚创建的集合
         */
        Set<NotifyListener> listeners = subscribed.get(url);
        if (listeners == null) {
            subscribed.putIfAbsent(url, new ConcurrentHashSet<NotifyListener>());
            listeners = subscribed.get(url);
        }
        listeners.add(listener);
    }

    /**
     * 先从缓存中移除，再通过子类 FailbackRegistry 具体执行取消订阅的逻辑
     *
     * @param url      Subscription condition, not allowed to be empty, e.g. consumer://10.20.153.10/com.alibaba.foo.BarService?version=1.0.0&application=kylin
     * @param listener A listener of the change event, not allowed to be empty
     */
    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("unsubscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("unsubscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unsubscribe: " + url);
        }
        Set<NotifyListener> listeners = subscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    /**
     * 在注册中心断开，重连成功会调用该方法，进行恢复注册和订阅
     *
     * @throws Exception
     */
    protected void recover() throws Exception {
        // register
        Set<URL> recoverRegistered = new HashSet<URL>(getRegistered());
        if (!recoverRegistered.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover register url " + recoverRegistered);
            }
            for (URL url : recoverRegistered) {
                register(url);
            }
        }
        // subscribe
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover subscribe url " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    subscribe(url, listener);
                }
            }
        }
    }


    protected void notify(List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            return;
        }

        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            URL url = entry.getKey();

            if (!UrlUtils.isMatch(url, urls.get(0))) {
                continue;
            }

            Set<NotifyListener> listeners = entry.getValue();
            if (listeners != null) {
                for (NotifyListener listener : listeners) {
                    try {
                        notify(url, listener, filterEmpty(url, urls));
                    } catch (Throwable t) {
                        logger.error("Failed to notify registry event, urls: " + urls + ", cause: " + t.getMessage(), t);
                    }
                }
            }
        }
    }

    /**
     * 通知监听器，URL变化结果，需要注意的是：
     * 1 向注册中心发起订阅后，会获取到全量数据，此时会被调用 #notify(...)方法，即Registry获取到了全量数据
     * 2 每次注册中心发生变更时，会调用 #notify(...)方法，虽然变化是增量，调用这个方法的调用方，已经进行处理，传入的 urls 依然是全量的
     * 3 数据流向： urls => {@link #notified} => {@link #properties} => {@link #file}
     *
     * @param url      订阅URL
     * @param listener 监听器
     * @param urls     通知的URL变化结果（全量数据）【todo 注意：每次传入的 urls 的“全量”，指的是至少要是一个分类的全量[动态类型的]，而不一定是全部数据】
     */
    protected void notify(URL url, NotifyListener listener, List<URL> urls) {
        if (url == null) {
            throw new IllegalArgumentException("notify url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("notify listener == null");
        }
        if ((urls == null || urls.isEmpty())
                && !Constants.ANY_VALUE.equals(url.getServiceInterface())) {
            logger.warn("Ignore empty notify urls for subscribe url " + url);
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Notify urls for subscribe url " + url + ", urls: " + urls);
        }

        // 将 `urls` 按照 `url.parameter.category` 分类，添加到Map集合result中
        Map<String, List<URL>> result = new HashMap<String, List<URL>>();
        for (URL u : urls) {
            if (UrlUtils.isMatch(url, u)) {
                String category = u.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
                List<URL> categoryList = result.get(category);
                if (categoryList == null) {
                    categoryList = new ArrayList<URL>();
                    result.put(category, categoryList);
                }
                categoryList.add(u);
            }
        }
        if (result.size() == 0) {
            return;
        }

        // 获得订阅URL对应的在 `notified` 中,通知的 URL 变化结果（全量数据），会把result中的值放入到 categoryNotified中
        Map<String, List<URL>> categoryNotified = notified.get(url);
        if (categoryNotified == null) {
            notified.putIfAbsent(url, new ConcurrentHashMap<String, List<URL>>());
            categoryNotified = notified.get(url);
        }

        // 处理通知的 URL 变化结果（全量数据），即按照分类，循环处理通知的URL变化结果（全量数据）
        for (Map.Entry<String, List<URL>> entry : result.entrySet()) {
            // 获得分类名
            String category = entry.getKey();
            // 获得分类名对应的通知ULR列表
            List<URL> categoryList = entry.getValue();
            // 1 将result 覆盖到 `notified`【更新notified集合中的通知ULR列表】，需要注意：当某个分类的数据为空时，会依然有 urls 。其中 `urls[0].protocol = empty` ，通过这样的方式，处理所有订阅URL对应的数据为空的情况。
            categoryNotified.put(category, categoryList);
            // 2 保存传入的url对应的被通知的URL到properties和文件保存 // todo 这个操作为什么要在循环体内，是要不断更新吗？
            saveProperties(url);
            // 3 调用传入的listener的notify()方法
            listener.notify(categoryList);
        }
    }


    /**
     * 1 按照url从ConcurrentMap<URL, Map<String, List<URL>>> notified中将Map<String, List<URL>>拿出来，之后将所有category的list组成一串buf（以空格分隔）
     * 2 将 serviceKey - buf 写入本地磁盘缓存中：Properties properties
     * 3 将AtomicLong lastCacheChanged加1
     * 4 之后根据syncSaveFile判断时同步保存properties到文件，还是异步保存properties到文件
     *
     * @param url
     */
    private void saveProperties(URL url) {
        if (file == null) {
            return;
        }

        try {
            StringBuilder buf = new StringBuilder();
            Map<String, List<URL>> categoryNotified = notified.get(url);
            if (categoryNotified != null) {
                for (List<URL> us : categoryNotified.values()) {
                    for (URL u : us) {
                        if (buf.length() > 0) {
                            buf.append(URL_SEPARATOR);
                        }
                        buf.append(u.toFullString());
                    }
                }
            }
            properties.setProperty(url.getServiceKey(), buf.toString());
            // 版本号，使用CAS
            long version = lastCacheChanged.incrementAndGet();
            if (syncSaveFile) {
                doSaveProperties(version);
            } else {
                registryCacheExecutor.execute(new SaveProperties(version));
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    /**
     * 销毁，主要是取消注册和订阅。需要注意的是，无论是服务提供者还是消费者，都会向Registry发起注册和订阅，所以都要进行取消
     */
    @Override
    public void destroy() {
        if (logger.isInfoEnabled()) {
            logger.info("Destroy registry:" + getUrl());
        }

        // 取消注册逻辑
        Set<URL> destroyRegistered = new HashSet<URL>(getRegistered());
        if (!destroyRegistered.isEmpty()) {
            for (URL url : new HashSet<URL>(getRegistered())) {
                if (url.getParameter(Constants.DYNAMIC_KEY, true)) {
                    try {
                        // 取消注册
                        unregister(url);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unregister url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unregister url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }

        // 取消订阅逻辑
        Map<URL, Set<NotifyListener>> destroySubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!destroySubscribed.isEmpty()) {
            for (Map.Entry<URL, Set<NotifyListener>> entry : destroySubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    try {
                        // 取消订阅
                        unsubscribe(url, listener);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unsubscribe url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unsubscribe url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return getUrl().toString();
    }

    private class SaveProperties implements Runnable {
        private long version;

        private SaveProperties(long version) {
            this.version = version;
        }

        @Override
        public void run() {
            doSaveProperties(version);
        }
    }

}
