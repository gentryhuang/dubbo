package com.alibaba.dubbo.examples.container;

import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.examples.container.api.DemoService;

/**
 * ApiConsumer
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/08/15
 * <p>
 * desc：
 */
public class ApiConsumer {

    public static void main(String[] args) throws InterruptedException {

        // 当前应用配置
        ApplicationConfig applicationConfig = new ApplicationConfig();
        applicationConfig.setName("api-config-demo-provider");

        // 连接注册中心配置
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setAddress("zookeeper://127.0.0.1:2181");

        // 引用远程服务，注意：ReferenceConfig为重对象，内部封装了与注册中心的连接，以及与服务提供方的连接，请自行缓存，否则可能造成内存和连接泄漏
        ReferenceConfig<DemoService> referenceConfig = new ReferenceConfig<DemoService>();
        referenceConfig.setApplication(applicationConfig);
        referenceConfig.setRegistry(registryConfig);
        referenceConfig.setInterface(DemoService.class);


        // 获取代理对象
        DemoService demoService = referenceConfig.get();

        while (true) {

            String ping = demoService.sayHello("ping");
            System.out.println(ping);

            Thread.sleep(3000);
        }

    }
}
