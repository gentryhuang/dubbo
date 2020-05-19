package com.code.resource.reading.consumer.xml;


import com.code.reading.service.IDemoService;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.IOException;

/**
 * XmlProvider
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/04/12
 * <p>
 * desc：
 */
public class Consumer {

    // 如果使用Multicast Registry 作为注册中心，需要debug模式运行，否则应用启动不起来

    public static String[] consumerPath = {"xml/consumer.xml"};
    public static String[] multiProtocolPath = {"xml/multi-protocol-provider.xml"};

    public static void main(String[] args) throws IOException {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(consumerPath);

        IDemoService consumerService = (IDemoService) context.getBean("resourceDemoService");
        String hello = consumerService.ping();
        System.out.println(hello);
        System.in.read();
    }

}
