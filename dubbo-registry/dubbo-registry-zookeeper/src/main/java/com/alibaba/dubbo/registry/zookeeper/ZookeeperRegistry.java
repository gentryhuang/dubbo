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
package com.alibaba.dubbo.registry.zookeeper;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.support.FailbackRegistry;
import com.alibaba.dubbo.remoting.zookeeper.ChildListener;
import com.alibaba.dubbo.remoting.zookeeper.StateListener;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperClient;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperTransporter;
import com.alibaba.dubbo.rpc.RpcException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ZookeeperRegistry
 */
public class ZookeeperRegistry extends FailbackRegistry {

    private final static Logger logger = LoggerFactory.getLogger(ZookeeperRegistry.class);

    /**
     * 默认端口
     */
    private final static int DEFAULT_ZOOKEEPER_PORT = 2181;

    /**
     * 默认Zookeeper根节点
     */
    private final static String DEFAULT_ROOT = "dubbo";

    /**
     * Zookeeper 根节点
     */
    private final String root;

    /**
     * Service 接口全名集合。该属性适合用于监控中心，订阅整个Service层，因为Service层是动态的，可以不断有新的Service服务发布（注意，不是服务实例）
     */
    private final Set<String> anyServices = new ConcurrentHashSet<String>();

    /**
     * 监听器集合，建立NotifyListener和ChildListener的映射关系，k1为订阅URL,k2为监听器,value为ChildListener【真正起作用的对象】
     */
    private final ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners = new ConcurrentHashMap<URL, ConcurrentMap<NotifyListener, ChildListener>>();

    /**
     * Zookeeper 客户端
     */
    private final ZookeeperClient zkClient;

    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        // 获取组名，默认为dubbo，url.parameters.group 参数值。从Zookeeper角度看就是根节点
        String group = url.getParameter(Constants.GROUP_KEY, DEFAULT_ROOT);
        if (!group.startsWith(Constants.PATH_SEPARATOR)) {
            // 订正路径： group = "/" + group;
            group = Constants.PATH_SEPARATOR + group;
        }
        // 确定根路径，以组名作为根路径
        this.root = group;
        // 创建 Zookeeper 客户端，默认为 CuratorZookeeperTransporter，由SPI确定具体的实例。创建好Zookeeper客户端，意味着注册中心的创建完成【Zookeeper服务端必需先启动，Dubbo应用作为Zookeeper的客户端进行连接，然后操作Zookeeper】
        zkClient = zookeeperTransporter.connect(url);
        // 添加 StateListener 状态监听器【这里知针对失败重连】，该监听器，在重连时，调用恢复方法 recover()，重新发起注册和订阅
        zkClient.addStateListener(new StateListener() {
            @Override
            public void stateChanged(int state) {
                if (state == RECONNECTED) {
                    try {
                        recover();
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
        });
    }

    static String appendDefaultPort(String address) {
        if (address != null && address.length() > 0) {
            int i = address.indexOf(':');
            if (i < 0) {
                return address + ":" + DEFAULT_ZOOKEEPER_PORT;
            } else if (Integer.parseInt(address.substring(i + 1)) == 0) {
                return address.substring(0, i + 1) + DEFAULT_ZOOKEEPER_PORT;
            }
        }
        return address;
    }

    @Override
    public boolean isAvailable() {
        return zkClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            // 关闭 Zookeeper 客户端连接
            zkClient.close();
        } catch (Exception e) {
            logger.warn("Failed to close zookeeper client " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 调用Zookeeper客户端创建服务节点
     *
     * @param url
     */
    @Override
    protected void doRegister(URL url) {
        try {
            /**
             * 1 通过 Zookeeper 客户端创建节点，节点路径由 toUrlPath 方法生成，路径格式如下: /${group}/${serviceInterface}/providers/${url}
             * 比如： /dubbo/com.alibaba.dubbo.demo.DemoService/providers/dubbo%3A%2F%2F127.0.0.1......
             * 2 url.parameters.dynamic ,是否动态数据。若为false，该数据为持久数据，当注册方退出时，数据仍然保存在注册中心
             */
            zkClient.create(toUrlPath(url), url.getParameter(Constants.DYNAMIC_KEY, true));
        } catch (Throwable e) {
            throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 取消注册，删除节点
     *
     * @param url
     */
    @Override
    protected void doUnregister(URL url) {
        try {
            zkClient.delete(toUrlPath(url));
        } catch (Throwable e) {
            throw new RpcException("Failed to unregister " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    protected void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            //----------------- 处理所有 Service层的 发起订阅，例如监控中心的订阅  -------------
            if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
                String root = toRootPath();
                // 获的订阅的url 对应的监听器集合
                ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                // 不存在，进行创建
                if (listeners == null) {
                    zkListeners.putIfAbsent(url, new ConcurrentHashMap<NotifyListener, ChildListener>());
                    listeners = zkListeners.get(url);
                }
                /**
                 *  获得NotifyListener 映射的 ChildListener对象。在Service层发生变更时，若是新增Service 接口全名时（即新增服务），调用subscribe(url,listener)方法，发起
                 *  该Service层的订阅【走 ---- 处理指定Service 层的发起订阅，例如服务消费这的订阅 ---------- 逻辑。是否是新增服务通过anyServices属性来判断
                 */
                ChildListener zkListener = listeners.get(listener);
                // 不存在ChildListener 对象，进行创建ChildListener对象
                if (zkListener == null) {
                    listeners.putIfAbsent(listener, new ChildListener() {
                        @Override
                        public void childChanged(String parentPath, List<String> currentChilds) {

                            for (String child : currentChilds) {

                                child = URL.decode(child);
                                // 新增Service 接口全名时（即新增服务），发起该Service层的订阅
                                if (!anyServices.contains(child)) {
                                    anyServices.add(child);
                                    subscribe(url.setPath(child).addParameters(Constants.INTERFACE_KEY, child,
                                            Constants.CHECK_KEY, String.valueOf(false)), listener);
                                }
                            }
                        }
                    });
                    zkListener = listeners.get(listener);
                }
                // 创建Service 节点，该节点为持久化节点
                zkClient.create(root, false);
                // 向Zookeeper service节点发起订阅
                List<String> services = zkClient.addChildListener(root, zkListener);
                // 首次全量数据获取完成时，循环Service接口全名数组，发起该Service 层的订阅，调用subscribe(url,listener)
                if (services != null && !services.isEmpty()) {
                    for (String service : services) {
                        service = URL.decode(service);
                        anyServices.add(service);
                        subscribe(url.setPath(service).addParameters(Constants.INTERFACE_KEY, service, Constants.CHECK_KEY, String.valueOf(false)), listener);
                    }
                }

                // ---- 处理指定Service 层 发起订阅，例如服务提供者订阅（订阅configurators,变化重新导出服务），服务消费者的订阅（订阅providers,configurators,routers，变化重新引入服务等） ----------
            } else {

                /**
                 * ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners
                 * 1 根据url获取ConcurrentMap<NotifyListener, ChildListener>，没有就创建
                 * 2 根据listener从ConcurrentMap<NotifyListener, ChildListener>获取ChildListener，没有就创建（创建的ChildListener用来监听子节点的变化）
                 * 3 创建path持久化节点
                 * 4 创建path子节点监听器
                 */

                // 子节点数据数组
                List<URL> urls = new ArrayList<URL>();

                // 循环分类数组，其中，调用toCategoriesPath(url)方法，获得分类数组，如：/dubbo/com.alibaba.dubbo.demo.DemoService/configurators
                for (String path : toCategoriesPath(url)) {
                    // 获得订阅的url 对应的监听器集合
                    ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                    // 不存在，进行创建
                    if (listeners == null) {
                        zkListeners.putIfAbsent(url, new ConcurrentHashMap<NotifyListener, ChildListener>());
                        listeners = zkListeners.get(url);
                    }
                    /**
                     * 对监听器集合处理 ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners。
                     * 获得listener(NotifyListener)对应的ChildListener对象，没有就会创建这里创建出来的ChildListener实例中的childChanged方法实际上
                     * 就是最终当parentPath[即toCategoriesPath方法处理后的元素path]下的currentChilds发生变化时，执行的逻辑。其中会回调NotifyListener#notify方法
                     */

                    ChildListener zkListener = listeners.get(listener);
                    if (zkListener == null) {
                        listeners.putIfAbsent(listener, new ChildListener() {
                            @Override
                            public void childChanged(String parentPath, List<String> currentChilds) {
                                // 变更时，调用 notity方法，回调 NotifyListener 【增量】,用来监听子节点列表的变化。todo 注意：即使是变动的情况下，传过来的也是全量数据，回头验证一下
                                ZookeeperRegistry.this.notify(url, listener, toUrlsWithEmpty(url, parentPath, currentChilds));
                            }
                        });
                        zkListener = listeners.get(listener);
                    }
                    // 创建 Type 节点，该节点为持久节点，如： /dubbo/com.alibaba.dubbo.demo.DemoService/configurators
                    zkClient.create(path, false);
                    /**
                     * 向Zookeeper path节点发起订阅，即使用AbstractZookeeperClient<TargetChildListener>的addChildListener(String path, final ChildListener listener)方法为path下的子节点
                     * 添加上边创建出来的内部类ChildListener实例，添加后返回子节点列表 【todo 重要，这里就是拉取类目下的全量数据】
                     */
                    List<String> children = zkClient.addChildListener(path, zkListener);
                    // 添加到urls 中
                    if (children != null) {
                        urls.addAll(toUrlsWithEmpty(url, path, children));
                    }
                }
                /**
                 * 首次全量数据获取完成时，调用NofityListener#notify(url,listener,currentChilds)方法，回调NotifyListener的逻辑
                 */
                notify(url, listener, urls); // 全量
            }
        } catch (Throwable e) {
            throw new RpcException("Failed to subscribe " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 真正取消订阅的逻辑，删除对应的监听器
     *
     * @param url
     * @param listener
     */
    @Override
    protected void doUnsubscribe(URL url, NotifyListener listener) {
        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
        if (listeners != null) {
            ChildListener zkListener = listeners.get(listener);
            if (zkListener != null) {
                if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
                    String root = toRootPath();
                    zkClient.removeChildListener(root, zkListener);
                } else {
                    for (String path : toCategoriesPath(url)) {
                        zkClient.removeChildListener(path, zkListener);
                    }
                }
            }
        }
    }

    /**
     * 查询符合条件的已注册数据，与订阅的推模式相对应，这里为拉模式，只返回一次结果
     *
     * @param url 查询条件，不允许为空， 如： consumer://10.20.153.10/com.alibaba.foo.BarService?version=1.0.0&application=kylin
     * @return 已注册信息列表，可能为空，含义同 {@link com.alibaba.dubbo.registry.NotifyListener#notify(List<URL>)}的参数
     * @see com.alibaba.dubbo.registry.NotifyListener#notify(List)
     */
    @Override
    public List<URL> lookup(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("lookup url == null");
        }
        try {
            // 循环分类数组，获得所有的URL数组
            List<String> providers = new ArrayList<String>();
            // toCategoriesPath(url) 获得分类数组
            for (String path : toCategoriesPath(url)) {
                // 获得分类下的URL列表
                List<String> children = zkClient.getChildren(path);
                if (children != null) {
                    providers.addAll(children);
                }
            }
            // 匹配
            return toUrlsWithoutEmpty(url, providers);
        } catch (Throwable e) {
            throw new RpcException("Failed to lookup " + url + " from zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 获得根目录
     * <p>
     * root
     *
     * @return
     */
    private String toRootDir() {
        if (root.equals(Constants.PATH_SEPARATOR)) {
            return root;
        }
        return root + Constants.PATH_SEPARATOR;
    }

    /**
     * Root
     *
     * @return 根路径
     */
    private String toRootPath() {
        return root;
    }

    /**
     * 获得服务路径
     * <p>
     * Root + service
     *
     * @param url
     * @return
     */
    private String toServicePath(URL url) {
        String name = url.getServiceInterface();
        if (Constants.ANY_VALUE.equals(name)) {
            return toRootPath();
        }
        return toRootDir() + URL.encode(name);
    }

    /**
     * 获得分类路径数组
     * Root + Service + Type
     *
     * @param url URL
     * @return 分类路径数组
     */
    private String[] toCategoriesPath(URL url) {
        String[] categories;
        // 如果category的值为 * ，表示分别订阅：providers,consumers,routers,configurators
        if (Constants.ANY_VALUE.equals(url.getParameter(Constants.CATEGORY_KEY))) {
            categories = new String[]{Constants.PROVIDERS_CATEGORY, Constants.CONSUMERS_CATEGORY,
                    Constants.ROUTERS_CATEGORY, Constants.CONFIGURATORS_CATEGORY};
        } else {
            // 如果category的值不为 * ，就取出 category的值，如果没有值，就把providers作为默认值。注意，当category的值不为空时会使用 '逗号' 分割category的值，为数组
            categories = url.getParameter(Constants.CATEGORY_KEY, new String[]{Constants.DEFAULT_CATEGORY});
        }
        // 获得分类路径数组
        String[] paths = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            // 在构建节点路径时，可能会对服务接口名进行加密，具体看toServicePath方法
            paths[i] = toServicePath(url) + Constants.PATH_SEPARATOR + categories[i];
        }
        return paths;
    }

    /**
     * 获得分类路径 如： 到 providers/consumers/routes/configurations 的路径
     *
     * @param url URL
     * @return 分类路径
     */
    private String toCategoryPath(URL url) {
        return toServicePath(url) + Constants.PATH_SEPARATOR + url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
    }

    /**
     * 获得URL 路径，即 要注册到注册中心上的完整路径
     * <p>
     * Root + Service + Type + URL
     * <p>
     * 被 {@link #doRegister(URL)} 和 {@link #doUnregister(URL)} 调用
     *
     * @param url URL
     * @return 路径
     */
    private String toUrlPath(URL url) {
        return toCategoryPath(url) + Constants.PATH_SEPARATOR + URL.encode(url.toFullString());
    }

    /**
     * 获得providers 中，和consumer 匹配的URL数组
     *
     * @param consumer  用于匹配的URL
     * @param providers 被匹配的URL的字符串
     * @return 匹配的URL数组
     */
    private List<URL> toUrlsWithoutEmpty(URL consumer, List<String> providers) {
        List<URL> urls = new ArrayList<URL>();
        if (providers != null && !providers.isEmpty()) {
            for (String provider : providers) {
                provider = URL.decode(provider);
                if (provider.contains("://")) { // 是URL
                    URL url = URL.valueOf(provider); // 将字符串转为URL
                    if (UrlUtils.isMatch(consumer, url)) { // 匹配
                        urls.add(url);
                    }
                }
            }
        }
        return urls;
    }

    /**
     * 1 获得providers 中，和consumer匹配的URL集合
     * 2 如果URL集合不为空，直接返回这个集合
     * 3 如果URL集合为空，首先从path中获取category的值，然后将consumer的协议换成empty并添加参数category=path中的category的值。
     * 形式：'empty://' 的URL返回，通过这样的方式，可以处理类似服务提供者为空的情况
     *
     * @param consumer  用于匹配URL 如：provider://10.1.22.101:20880/com.alibaba.dubbo.demo.DemoService?key=value&...
     * @param path      被匹配的URL的字符串 如：/dubbo/com.alibaba.dubbo.demo.DemoService/configurators
     * @param providers 匹配的URL数组
     * @return
     */
    private List<URL> toUrlsWithEmpty(URL consumer, String path, List<String> providers) {
        // 获得 providers中，和consumer 匹配的URL数组
        List<URL> urls = toUrlsWithoutEmpty(consumer, providers);
        // 如果不存在匹配，则创建 'empty://' 的URL返回
        if (urls == null || urls.isEmpty()) {
            int i = path.lastIndexOf('/');
            String category = i < 0 ? path : path.substring(i + 1);
            URL empty = consumer.setProtocol(Constants.EMPTY_PROTOCOL).addParameter(Constants.CATEGORY_KEY, category);
            urls.add(empty);
        }
        return urls;
    }

}
