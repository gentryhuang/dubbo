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
package com.alibaba.dubbo.config;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.bytecode.Wrapper;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import com.alibaba.dubbo.config.model.ApplicationModel;
import com.alibaba.dubbo.config.model.ProviderModel;
import com.alibaba.dubbo.config.support.Parameter;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.ServiceClassHolder;
import com.alibaba.dubbo.rpc.cluster.ConfiguratorFactory;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.alibaba.dubbo.common.utils.NetUtils.LOCALHOST;
import static com.alibaba.dubbo.common.utils.NetUtils.getAvailablePort;
import static com.alibaba.dubbo.common.utils.NetUtils.getLocalHost;
import static com.alibaba.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static com.alibaba.dubbo.common.utils.NetUtils.isInvalidPort;

/**
 * ServiceConfig 服务提供者暴露服务配置类
 *
 * @export
 */
public class ServiceConfig<T> extends AbstractServiceConfig {

    private static final long serialVersionUID = 3033787999037024738L;

    /**
     * 自适应 Protocol实现对象
     */
    private static final Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();
    /**
     * 自适应 ProxyFactory 实现对象
     */
    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    private static final ScheduledExecutorService delayExportExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));
    private final List<URL> urls = new ArrayList<URL>();
    /**
     * 服务配置暴露的Exporter:
     * URL: Exporter 不一定是 1:1 的关系，需要看scope的值：
     * 1 scope 未设置时，会暴露Local + Remote两个，也就是URL : Exporter = 1:2
     * 2 scope设置为空时，不会暴露，也就是URL:Exporter = 1:0
     * 3 scope甚至为local 或 Remote 任一个时，会暴露对应的，也就是URL:Exporter = 1:1
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();
    // interface type
    private String interfaceName;
    /**
     * 非配置，通过interfaceName 通过反射获得
     */
    private Class<?> interfaceClass;
    // 服务接口的实现对象
    private T ref;
    // service name
    private String path;
    // method configuration
    private List<MethodConfig> methods;
    private ProviderConfig provider;
    private transient volatile boolean exported;

    private transient volatile boolean unexported;

    private volatile String generic;

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        appendAnnotation(Service.class, service);
    }

    @Deprecated
    private static List<ProtocolConfig> convertProviderToProtocol(List<ProviderConfig> providers) {
        if (providers == null || providers.isEmpty()) {
            return null;
        }
        List<ProtocolConfig> protocols = new ArrayList<ProtocolConfig>(providers.size());
        for (ProviderConfig provider : providers) {
            protocols.add(convertProviderToProtocol(provider));
        }
        return protocols;
    }

    @Deprecated
    private static List<ProviderConfig> convertProtocolToProvider(List<ProtocolConfig> protocols) {
        if (protocols == null || protocols.isEmpty()) {
            return null;
        }
        List<ProviderConfig> providers = new ArrayList<ProviderConfig>(protocols.size());
        for (ProtocolConfig provider : protocols) {
            providers.add(convertProtocolToProvider(provider));
        }
        return providers;
    }

    @Deprecated
    private static ProtocolConfig convertProviderToProtocol(ProviderConfig provider) {
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName(provider.getProtocol().getName());
        protocol.setServer(provider.getServer());
        protocol.setClient(provider.getClient());
        protocol.setCodec(provider.getCodec());
        protocol.setHost(provider.getHost());
        protocol.setPort(provider.getPort());
        protocol.setPath(provider.getPath());
        protocol.setPayload(provider.getPayload());
        protocol.setThreads(provider.getThreads());
        protocol.setParameters(provider.getParameters());
        return protocol;
    }

    @Deprecated
    private static ProviderConfig convertProtocolToProvider(ProtocolConfig protocol) {
        ProviderConfig provider = new ProviderConfig();
        provider.setProtocol(protocol);
        provider.setServer(protocol.getServer());
        provider.setClient(protocol.getClient());
        provider.setCodec(protocol.getCodec());
        provider.setHost(protocol.getHost());
        provider.setPort(protocol.getPort());
        provider.setPath(protocol.getPath());
        provider.setPayload(protocol.getPayload());
        provider.setThreads(protocol.getThreads());
        provider.setParameters(protocol.getParameters());
        return provider;
    }

    private static Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        if (RANDOM_PORT_MAP.containsKey(protocol)) {
            return RANDOM_PORT_MAP.get(protocol);
        }
        return Integer.MIN_VALUE;
    }

    private static void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
        }
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    @Parameter(excluded = true)
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    public boolean isUnexported() {
        return unexported;
    }

    /**
     * 暴露服务入口，加jvm锁
     */
    public synchronized void export() {
        // 当export 或者 delay 未配置时，从ProviderConfig对象读取
        if (provider != null) {
            if (export == null) {
                export = provider.getExport();
            }
            if (delay == null) {
                delay = provider.getDelay();
            }
        }
        // 不暴露服务(export = false),则不进行暴露服务逻辑
        if (export != null && !export) {
            return;
        }

        // 延迟暴露的话，就是使用任务线程池ScheduledExecutorService处理
        if (delay != null && delay > 0) {
            delayExportExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    doExport();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } else {
            doExport();
        }
    }

    /**
     * 服务暴露，jvm锁
     */
    protected synchronized void doExport() {
        // 检查是否可以暴露，若可以，标记已经暴露然后执行服务暴露逻辑
        if (unexported) {
            throw new IllegalStateException("Already unexported!");
        }
        // 如果已经暴露了直接返回
        if (exported) {
            return;
        }
        // 标记已经暴露过了
        exported = true;
        // 校验interfaceName 是否合法，即接口名非空
        if (interfaceName == null || interfaceName.length() == 0) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }

        // 校验provider是否为空，为空则新建一个，并拼接属性配置（环境变量 + .properties文件中的 属性）到ProviderConfig对象
        checkDefault();

        // 检测application，module等核心配置类对象是否为空，若为空则尝试从其他配置类对象中获取对应的实例。即： 从ProviderConfig 对象中，读取application,module,registries,monitor,protocols配置对象
        if (provider != null) {
            if (application == null) {
                application = provider.getApplication();
            }
            if (module == null) {
                module = provider.getModule();
            }
            if (registries == null) {
                registries = provider.getRegistries();
            }
            if (monitor == null) {
                monitor = provider.getMonitor();
            }
            if (protocols == null) {
                protocols = provider.getProtocols();
            }
        }
        // 从ModuleConfig 对象中，读取registries,monitor配置对象
        if (module != null) {
            if (registries == null) {
                registries = module.getRegistries();
            }
            if (monitor == null) {
                monitor = module.getMonitor();
            }
        }
        // 从ApplicationConfig 对象中，读取registries,monitor配置对象
        if (application != null) {
            if (registries == null) {
                registries = application.getRegistries();
            }
            if (monitor == null) {
                monitor = application.getMonitor();
            }
        }

        // 检测ref是否泛化接口的实现
        if (ref instanceof GenericService) {
            // 设置 interfaceClass 为 GenericService.class
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                // 设置 generic = "true"
                generic = Boolean.TRUE.toString();
            }
            // 普通接口的实现
        } else {
            try {
                // 通过反射获取对应的接口的Class
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 检验接口和方法 （接口非空，方法都在接口中定义）
            checkInterfaceAndMethods(interfaceClass, methods);
            // 校验引用ref是否实现了当前接口
            checkRef();
            // 标记为非泛化实现
            generic = Boolean.FALSE.toString();
        }
        // 处理服务接口客户端本地代理,即本地存根（local 属性 -> AbstractInterfaceConfig#setLocal）。目前已经废弃，此处主要用于兼容，使用stub属性
        if (local != null) {
            // 如果local属性设置为ture，表示使用缺省代理类名，即：接口名 + Local 后缀
            if ("true".equals(local)) {
                local = interfaceName + "Local";
            }
            Class<?> localClass;
            try {
                // 获取本地存根类
                localClass = ClassHelper.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 检测本地存根类是否可赋值给接口类，若不可赋值则会抛出异常，提醒使用者本地存根类类型不合法
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }

        // 处理服务接口客户端本地代理(stub 属性)相关，即本地存根
        if (stub != null) {
            // 如果stub属性设置为ture，表示使用缺省代理类名，即：接口名 + Stub 后缀
            if ("true".equals(stub)) {
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                // 获取本地存根类
                stubClass = ClassHelper.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 判断interfaceClass 是否是 stubClass 的接口，即 检测本地存根类是否可赋值给接口类，若不可赋值则会抛出异常，提醒使用者本地存根类类型不合法
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }

        /* 检测各种对象是否为空，为空则新建，或者抛出异常*/

        // 校验ApplicationConfig配置
        checkApplication();
        // 校验RegistryConfig配置
        checkRegistry();
        // 校验ProtocolConfig配置数组
        checkProtocol();
        // 读取环境变量和properties配置到ServiceConfig对象（自己）
        appendProperties(this);
        // 校验Stub和Mock相关的配置
        checkStubAndMock(interfaceClass);
        // 服务路径，缺省是接口名
        if (path == null || path.length() == 0) {
            path = interfaceName;
        }
        // 暴露服务
        doExportUrls();
        /** todo 作用是什么？
         * 1 ProviderModel 表示服务提供者模型，此对象中存储了和服务提供者相关的信息，比如服务的配置信息，服务实例等。每个被导出的服务对应一个 ProviderModel
         * 2 ApplicationModel 持有所有的 ProviderModel
         */
        ProviderModel providerModel = new ProviderModel(getUniqueServiceName(), this, ref);
        ApplicationModel.initProviderModel(getUniqueServiceName(), providerModel);
    }

    private void checkRef() {
        // reference should not be null, and is the implementation of the given interface
        if (ref == null) {
            throw new IllegalStateException("ref not allow null!");
        }
        // 引用ref是否实现了当前的接口
        if (!interfaceClass.isInstance(ref)) {
            throw new IllegalStateException("The class "
                    + ref.getClass().getName() + " unimplemented interface "
                    + interfaceClass + "!");
        }
    }

    public synchronized void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("unexpected err when unexport" + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;
    }

    /**
     * Dubbo 允许我们使用不同的协议导出服务，也允许我们向多个注册中心注册服务。Dubbo 在 doExportUrls 方法中对多协议，多注册中心进行了支持
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        // 加载注册中心URL 数组 【协议已经处理过，不再是配置的注册中心协议 如：zookeeper ,而是统一替换成了registry】
        List<URL> registryURLs = loadRegistries(true);
        // 遍历协议集合，支持多协议暴露。
        for (ProtocolConfig protocolConfig : protocols) {
            doExportUrlsFor1Protocol(protocolConfig, registryURLs);
        }
    }

    /**
     * 使用不同的协议，逐个向注册中心分组暴露服务。该方法中包含了本地和远程两种暴露方式
     *
     * @param protocolConfig 协议配置对象
     * @param registryURLs   处理过的注册中心分组集合【已经添加了ApplicationConfig和RegistryConfig的参数】
     */
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        // 协议名
        String name = protocolConfig.getName();
        // 协议名为空时，缺省设置为 dubbo
        if (name == null || name.length() == 0) {
            name = "dubbo";
        }
        // 创建参数集合map，用于Dubbo URL 的构建（服务提供者URL）
        Map<String, String> map = new HashMap<String, String>();

        // 将side,dubbo,timestamp,pid参数，添加到map集合中
        map.put(Constants.SIDE_KEY, Constants.PROVIDER_SIDE);
        map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
        // 通过反射将各种配置对象中的属性添加到map集合中，map用于URL的构建【注意属性覆盖问题】
        appendParameters(map, application);
        appendParameters(map, module);
        appendParameters(map, provider, Constants.DEFAULT_KEY);
        appendParameters(map, protocolConfig);
        appendParameters(map, this);


        // 将MethodConfig 对象数组添加到 map 集合中。就是将每个MethodConfig和其对应的ArgumentConfig对象数组添加到map中【处理方法相关的属性到map】
        if (methods != null && !methods.isEmpty()) {
            // methods 为 MethodConfig 集合，MethodConfig 中存储了 <dubbo:method> 标签的配置信息
            for (MethodConfig method : methods) {
                /**
                 * 将MethodConfig对象的属性添加到map集合中，其中属性键 = 方法名.属性名。如：
                 * <dubbo:method name="sleep" retries="2"></dubbo:method>对应的MethodConfig，属性到map的格式 map={"sleep.retries":2,...}
                 */
                appendParameters(map, method, method.getName());

                // 当配置了 MehodConfig.retry = false 时，强制禁用重试
                String retryKey = method.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    // 检测 MethodConfig retry 是否为 false，若是，则设置重试次数为0
                    if ("false".equals(retryValue)) {
                        map.put(method.getName() + ".retries", "0");
                    }
                }
                // 将MethodConfig下的ArgumentConfig 对象数组，添加到 map 集合中
                List<ArgumentConfig> arguments = method.getArguments();
                if (arguments != null && !arguments.isEmpty()) {
                    for (ArgumentConfig argument : arguments) {
                        // 检测type 属性是否为空，
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            // 通过反射取出接口的方法列表
                            Method[] methods = interfaceClass.getMethods();
                            // 遍历接口中的方法列表
                            if (methods != null && methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // 比对方法名，查找目标方法
                                    if (methodName.equals(method.getName())) {
                                        // 通过反射取出目标方法的参数类型列表
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // 若果配置index配置项，且值不为-1
                                        if (argument.getIndex() != -1) {
                                            // 从argtypes数组中获取下标index处的元素argType，并检测ArgumentConfig中的type属性与argType名称是否一致，不一致则抛出异常
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                // 将ArgumentConfig对象的属性添加到map集合中，键前缀=方法名.index，如：map = {"sleep.2":true}
                                                appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // 遍历参数类型数组argtypes，查找argument.type类型的参数
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                // 从参数类型列表中查找类型名称为argument.type的参数
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    // 将ArgumentConfig对象的属性添加到map集合中
                                                    appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException("argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            // 用户未配置 type 属性，但配置了index属性，且index != -1
                        } else if (argument.getIndex() != -1) { // 指定单个参数的位置
                            // 将ArgumentConfig对象的属性添加到map集合中
                            appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            throw new IllegalArgumentException("argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }


        //--- 检测 generic 是否 为 true ,并根据检测结果向map中添加不同的信息 ---/
        // 将 generic,methods,revision 加入到数组
        if (ProtocolUtils.isGeneric(generic)) {
            map.put(Constants.GENERIC_KEY, generic);
            map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
        } else {
            // 先从MAINFEST.MF 中获取版本号，若获取不到，再从jar包命名中可能带的版本号作为结果，如 2.6.5.RELEASE。若都不存在，返回默认版本号【源码运行可能会没有】
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put("revision", revision); // 修订号
            }
            // 为接口生成包裹类 Wrapper，Wrapper 中包含了接口的详细信息，比如接口方法名数组，字段信息等【Dubbo 自定义功能类】
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            // 添加方法名到 map 中，如果包含多个方法名，则用逗号隔开，比如：method=a,b
            if (methods.length == 0) {
                logger.warn("NO method found in service interface " + interfaceClass.getName());
                // 没有方法名就添加 method=*
                map.put(Constants.METHODS_KEY, Constants.ANY_VALUE);
            } else {
                // 将逗号作为分隔符连接方法名，并将连接后的字符串放入 map 中
                map.put(Constants.METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }

        // token 【是暴露出去的服务更安全，使用token做安全校验】
        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                map.put(Constants.TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(Constants.TOKEN_KEY, token);
            }
        }
        // 协议为injvm时，不注册，不通知
        if (Constants.LOCAL_PROTOCOL.equals(protocolConfig.getName())) {
            protocolConfig.setRegister(false);
            map.put("notify", "false");
        }
        // 获得基础路径
        String contextPath = protocolConfig.getContextpath();
        if ((contextPath == null || contextPath.length() == 0) && provider != null) {
            contextPath = provider.getContextpath();
        }
        // 获得注册到注册中心的服务提供者host，并为map设置bind.ip , anyhost 两个key
        String host = this.findConfigedHosts(protocolConfig, registryURLs, map);
        // 获取端口，并为map设置bing.port key
        Integer port = this.findConfigedPorts(protocolConfig, name, map);


        /**
         * 创建Dubbo URL对象 【注意这里的 path 的值】
         * 1 name: 协议名
         * 2 host: 主机名
         * 3 port: 端口
         * 4 path: 【基础路径】/path
         * 5 parameters: 属性集合map
         */
        URL url = new URL(name, host, port, (contextPath == null || contextPath.length() == 0 ? "" : contextPath + "/") + path, map);

        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class).hasExtension(url.getProtocol())) {
            // 加载ConfiguratorFactory ，并生成Configurator 配置 url
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class).getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        // 从URL中获取暴露方式
        String scope = url.getParameter(Constants.SCOPE_KEY);
        // don't export when none is configured
        if (!Constants.SCOPE_NONE.toString().equalsIgnoreCase(scope)) {

            // 本地暴露
            if (!Constants.SCOPE_REMOTE.toString().equalsIgnoreCase(scope)) {
                exportLocal(url);
            }

            // 远程暴露过程：包含了服务导出和服务注册两个过程
            if (!Constants.SCOPE_LOCAL.toString().equalsIgnoreCase(scope)) {
                if (logger.isInfoEnabled()) {
                    logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                }
                if (registryURLs != null && !registryURLs.isEmpty()) {
                    // 遍历注册中心URL数组
                    for (URL registryURL : registryURLs) {
                        // dynamic属性：服务是否动态注册，如果设为false,注册后将显示disable状态，需要人工启用，并且服务提供者停止时，也不会自动下线，需要人工禁用
                        url = url.addParameterIfAbsent(Constants.DYNAMIC_KEY, registryURL.getParameter(Constants.DYNAMIC_KEY));
                        // 获取监控中心URL
                        URL monitorUrl = loadMonitor(registryURL);
                        if (monitorUrl != null) {
                            // 监控URL不能空，就将监控中心的URL作为monitor参数添加到服务提供者的URL中，并且需要编码。通过这样方式，服务提供者的URL中就包含了监控中心的配置
                            url = url.addParameterAndEncoded(Constants.MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL);
                        }

                        // For providers, this is used to enable custom proxy to generate invoker
                        // todo ???
                        String proxy = url.getParameter(Constants.PROXY_KEY);
                        if (StringUtils.isNotEmpty(proxy)) {
                            registryURL = registryURL.addParameter(Constants.PROXY_KEY, proxy);
                        }
                        /**
                         * 1 使用ProxyFactory 创建 Invoker 对象【执行Invoker#invoke方法时，内部会调用Service对象(ref)对应的调用方法】
                         * 2 注意，这和本地暴露的参数不一样，本地暴露传入的直接是服务提供者的URL，而远程暴露需要传入注册中心URL及服务提供者URL的信息
                         * 3 在将服务实例ref转成Invoker后，如果有注册中心时，则会通过RegisterProtocol#export进行更细粒度的控制，如先进行服务暴露再注册服务元数据，这个过程注册中心依次进行：
                         * （1）委托具体协议进行服务暴露，如Dubbo协议进行服务暴露，创建NettyServer监听端口和保存服务实例
                         * （2）创建注册中心对象，与注册中心进行TCP连接
                         * （3）注册服务元数据到注册中心
                         * （4）订阅configurators节点，监听服务动态属性变更事件
                         * （5）服务销毁收尾工作，如关闭端口，移除注册的元数据
                         */
                        Invoker<?> invoker = proxyFactory.getInvoker(
                                ref,
                                (Class) interfaceClass,
                                registryURL.addParameterAndEncoded(Constants.EXPORT_KEY, url.toFullString())
                        );

                        // 创建 DelegateProviderMetaDataInvoker 对象，在Invoker对象基础上，增加了当前服务提供者ServiceConfig对象，即把Invoker和ServiceConfig包在了一起
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                        /**
                         * 导出服务，生成Exporter:
                         *
                         * 1 使用Protocol 暴露Invoker 对象。具体使用哪个协议，Dubbo SPI自动根据URL参数获得对应的拓展实现
                         * 2 Protocol 有两个Wrapper拓展实现类，ProtocolFilterWrapper和ProtocolListenerWrapper,export方法调用顺序：
                         * Protocol$Adaptive=>ProtocolListenerWrapper=>ProtocolFilterWrapper=>RegistryProtocol
                         * =>
                         * Protocol$Adaptive=>ProtocolListenerWrapper=>ProtocolFilterWrapper=>DubboProtocol
                         * 即：这一条大的调用链包含两条小的调用链：
                         * <1> 首先，传入的是注册中心的URL,通过Protocol$Adaptive 获取到的是 RegistryProtocol对象
                         * <2> 然后，RegistryProtocol会在其#export方法中，使用服务提供者的URL(即注册中心的URL的export参数值)，再次调用Protocol$Adaptive获取到的是DubboProtocol对象进行服务暴露
                         */
                        Exporter<?> exporter = protocol.export(wrapperInvoker);
                        // 添加到 Exporter 集合
                        exporters.add(exporter);
                    }
                } else { // 当配置注册中心为 N/A 时，表示即使远程暴露服务，也不向注册中心注册，这种方式用于服务消费者直连服务提供者。仅导出服务即可
                    // 使用ProxyFactory 创建 Invoker 对象
                    Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
                    // 创建 DelegateProviderMetaDataInvoker 对象
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                    // 使用Protocol 暴露Invoker 对象
                    Exporter<?> exporter = protocol.export(wrapperInvoker);
                    // 添加到 Exporter 集合
                    exporters.add(exporter);
                }
            }
        }
        this.urls.add(url);
    }

    /**
     * 本地暴露
     *
     * @param url
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void exportLocal(URL url) {
        /**
         * 1 若果URl的协议头是injvm，说明已经暴露到本地了，无需再次暴露
         * 2 非injvm协议【伪协议】 就基于原有的url创建新的服务的本地Dubbo URL对象,并重置协议为injvm，主机地址 127.0.0.1，端口为0
         */
        if (!Constants.LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
            URL local = URL.valueOf(url.toFullString())
                    .setProtocol(Constants.LOCAL_PROTOCOL)
                    .setHost(LOCALHOST)
                    .setPort(0);
            // 添加服务的真实类，就是服务接口的实现类【仅用于RestProtocol协议】
            ServiceClassHolder.getInstance().pushServiceClass(getServiceClass(ref));
            /**
             * 1 使用ProxyFactory 创建 Invoker对象
             *   （1）Invoker#invoke(invocation)执行时，内部会调用Service对象(ref)对应的方法
             *   （2）使用哪个ProxyFactory,Dubbo SPI会自动根据URL参数获得对应的扩展实现
             * 2 使用Protocol暴露 Invoker对象：
             *   （1）使用哪个Protocol,Dubbo SPI会自动根据URL参数获得对应的扩展实现
             *   （2）这里创建Invoker时，URL的protocol为injvm，此时Protocol的扩展实现就是InjvmProtocol
             *    (3) 由Dubbo的特性AOP原理，Protocol有两个Wrapper拓展实现类：ProtocolFilterWrapper和ProtocolListenerWrapper，export方法调用顺序：
             *       Protocol$Adaptive=>ProtocolListenerWrapper==>ProtocolFilterWrapper=>InjvmProtocol
             */
            Invoker invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, local);
            Exporter<?> exporter = protocol.export(invoker);
            // 添加到Exporter集合中
            exporters.add(exporter);
            logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry");
        }
    }

    protected Class getServiceClass(T ref) {
        return ref.getClass();
    }

    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig, List<URL> registryURLs, Map<String, String> map) {
        boolean anyhost = false;

        String hostToBind = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_BIND);
        if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) {
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + Constants.DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // if bind ip is not found in environment, keep looking up
        if (hostToBind == null || hostToBind.length() == 0) {
            hostToBind = protocolConfig.getHost();
            if (provider != null && (hostToBind == null || hostToBind.length() == 0)) {
                hostToBind = provider.getHost();
            }
            if (isInvalidLocalHost(hostToBind)) {
                anyhost = true;
                try {
                    hostToBind = InetAddress.getLocalHost().getHostAddress();
                } catch (UnknownHostException e) {
                    logger.warn(e.getMessage(), e);
                }
                if (isInvalidLocalHost(hostToBind)) {
                    if (registryURLs != null && !registryURLs.isEmpty()) {
                        for (URL registryURL : registryURLs) {
                            if (Constants.MULTICAST.equalsIgnoreCase(registryURL.getParameter("registry"))) {
                                // skip multicast registry since we cannot connect to it via Socket
                                continue;
                            }
                            try {
                                Socket socket = new Socket();
                                try {
                                    SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                                    socket.connect(addr, 1000);
                                    hostToBind = socket.getLocalAddress().getHostAddress();
                                    break;
                                } finally {
                                    try {
                                        socket.close();
                                    } catch (Throwable e) {
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn(e.getMessage(), e);
                            }
                        }
                    }
                    if (isInvalidLocalHost(hostToBind)) {
                        hostToBind = getLocalHost();
                    }
                }
            }
        }

        map.put(Constants.BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        String hostToRegistry = getValueFromConfig(protocolConfig, Constants.DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (hostToRegistry == null || hostToRegistry.length() == 0) {
            // bind ip is used as registry ip by default
            hostToRegistry = hostToBind;
        }

        map.put(Constants.ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }

    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @return
     */
    private Integer findConfigedPorts(ProtocolConfig protocolConfig, String name, Map<String, String> map) {
        Integer portToBind = null;

        // parse bind port from environment
        String port = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }
            if (portToBind == null || portToBind <= 0) {
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    portToBind = getAvailablePort(defaultPort);
                    putRandomPort(name, portToBind);
                }
                logger.warn("Use random available port(" + portToBind + ") for protocol " + name);
            }
        }

        // save bind port, used as url's key later
        map.put(Constants.BIND_PORT_KEY, String.valueOf(portToBind));

        // registry port, not used as bind port by default
        String portToRegistryStr = getValueFromConfig(protocolConfig, Constants.DUBBO_PORT_TO_REGISTRY);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            portToRegistry = portToBind;
        }

        return portToRegistry;
    }

    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String port = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (port == null || port.length() == 0) {
            port = ConfigUtils.getSystemProperty(key);
        }
        return port;
    }

    private void checkDefault() {
        if (provider == null) {
            provider = new ProviderConfig();
        }
        appendProperties(provider);
    }

    private void checkProtocol() {
        if ((protocols == null || protocols.isEmpty())
                && provider != null) {
            setProtocols(provider.getProtocols());
        }
        // backward compatibility
        if (protocols == null || protocols.isEmpty()) {
            setProtocol(new ProtocolConfig());
        }
        for (ProtocolConfig protocolConfig : protocols) {
            if (StringUtils.isEmpty(protocolConfig.getName())) {
                protocolConfig.setName(Constants.DUBBO_VERSION_KEY);
            }
            appendProperties(protocolConfig);
        }
    }

    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (ref instanceof GenericService) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (id == null || id.length() == 0) {
            id = interfaceName;
        }
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public T getRef() {
        return ref;
    }

    public void setRef(T ref) {
        this.ref = ref;
    }

    @Parameter(excluded = true)
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        checkPathName(Constants.PATH_KEY, path);
        this.path = path;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    // ======== Deprecated ========

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ProviderConfig getProvider() {
        return provider;
    }

    public void setProvider(ProviderConfig provider) {
        this.provider = provider;
    }

    public String getGeneric() {
        return generic;
    }

    public void setGeneric(String generic) {
        if (StringUtils.isEmpty(generic)) {
            return;
        }
        if (ProtocolUtils.isGeneric(generic)) {
            this.generic = generic;
        } else {
            throw new IllegalArgumentException("Unsupported generic type " + generic);
        }
    }

    public List<URL> getExportedUrls() {
        return urls;
    }

    /**
     * @deprecated Replace to getProtocols()
     */
    @Deprecated
    public List<ProviderConfig> getProviders() {
        return convertProtocolToProvider(protocols);
    }

    /**
     * @deprecated Replace to setProtocols()
     */
    @Deprecated
    public void setProviders(List<ProviderConfig> providers) {
        this.protocols = convertProviderToProtocol(providers);
    }

    @Parameter(excluded = true)
    public String getUniqueServiceName() {
        StringBuilder buf = new StringBuilder();
        if (group != null && group.length() > 0) {
            buf.append(group).append("/");
        }
        buf.append(interfaceName);
        if (version != null && version.length() > 0) {
            buf.append(":").append(version);
        }
        return buf.toString();
    }
}
