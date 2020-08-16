package com.alibaba.dubbo.examples.container;

import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.ServiceConfig;
import com.alibaba.dubbo.container.Main;
import com.alibaba.dubbo.examples.container.api.DemoService;
import com.alibaba.dubbo.examples.container.impl.DemoServiceImpl;

import java.io.IOException;

/**
 * ApiProvider
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/08/05
 * <p>
 * desc：
 */
public class ApiProvider {
    public static void main(String[] args) throws IOException {

        // 服务对象
        DemoService demoService = new DemoServiceImpl();

        // 应用配置
        ApplicationConfig applicationConfig = new ApplicationConfig();
        applicationConfig.setName("api-config-demo-provider");

        // 连接注册中心配置
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setAddress("zookeeper://127.0.0.1:2181");

        // 服务提供者协议配置
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName("dubbo");
        protocolConfig.setPort(20880);

        //省略ServiceConfig的其它配置项，如Module、Provider、Monitor等

        // 服务提供者暴露服务配置，注意：ServiceConfig为重对象，内部封装了与注册中心的连接，以及开启服务端口，请自行缓存，否则可能造成内存和连接泄漏
        ServiceConfig<DemoService> serviceConfig = new ServiceConfig<DemoService>();
        serviceConfig.setApplication(applicationConfig);
        serviceConfig.setRegistry(registryConfig);
        serviceConfig.setProtocol(protocolConfig);
        serviceConfig.setInterface(DemoService.class);
        serviceConfig.setRef(demoService);

        // 暴露及注册服务
        serviceConfig.export();

        // 阻主线程，防止服务关闭，用于消费者的调用
        System.in.read();

    }
}
