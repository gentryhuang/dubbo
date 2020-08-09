package com.code.reading.api;


import com.code.reading.service.IDemoService;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;

/**
 * ServiceReference
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/08/04
 * <p>
 * desc：
 */
public class ServiceReference {
    public static void main(String[] args) throws InterruptedException {
        // 当前应用配置
        ApplicationConfig application = new ApplicationConfig();
        application.setName("app-api-consumer");

// 连接注册中心配置
        RegistryConfig registry = new RegistryConfig();
        registry.setAddress("zookeeper://127.0.0.1:2181");
       // registry.setUsername("aaa");
        //registry.setPassword("bbb");

// 注意：ReferenceConfig为重对象，内部封装了与注册中心的连接，以及与服务提供方的连接

// 引用远程服务
        ReferenceConfig<IDemoService> reference = new ReferenceConfig<>(); // 此实例很重，封装了与注册中心的连接以及与提供者的连接，请自行缓存，否则可能造成内存和连接泄漏
        reference.setApplication(application);
        reference.setRegistry(registry); // 多个注册中心可以用setRegistries()
        reference.setInterface(IDemoService.class);
        reference.setVersion("1.0.0");
        reference.setCheck(Boolean.FALSE);

        while (true){
            // 和本地bean一样使用xxxService
            IDemoService demoService = reference.get(); // 注意：此代理对象内部封装了所有通讯细节，对象较重，请缓存复用

            Thread.sleep(5000);

            demoService.ping("pong");
        }


    }
}
