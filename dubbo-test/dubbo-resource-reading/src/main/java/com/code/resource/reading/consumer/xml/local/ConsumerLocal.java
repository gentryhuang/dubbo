package com.code.resource.reading.consumer.xml.local;


import com.code.resource.reading.api.DemoService;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.io.IOException;

/**
 * XmlProvider
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/04/12
 * <p>
 * desc：
 */
public class ConsumerLocal {

    // 如果使用Multicast Registry 作为注册中心，需要debug模式运行，否则应用启动不起来

    public static String[] consumerPath = {"xml/consumer-injvm.xml"};

    public static void main(String[] args) throws IOException{
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(consumerPath);
        System.out.println("Spring 初始化完成");

        DemoService consumerService = (DemoService) context.getBean("demoService");
        String hello = consumerService.hello();
        System.out.println(hello);
        System.in.read();
    }

}
