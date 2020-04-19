package com.code.resource.reading.xml.client;

import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Client
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/04/19
 * <p>
 * desc：
 */
public class Client {
    public static void main(String[] args) throws Exception {

        // 如果使用Multicast Registry 作为注册中心，需要debug模式运行，否则应用启动不起来

        //Prevent to get IPV6 address,this way only work in debug mode
        //But you can pass use -Djava.net.preferIPv4Stack=true,then it work well whether in debug mode or not

        System.setProperty("java.net.preferIPv4Stack", "true");
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(new String[]{"spring/xml-provider.xml"});
        context.start();

        System.in.read();
    }
}
