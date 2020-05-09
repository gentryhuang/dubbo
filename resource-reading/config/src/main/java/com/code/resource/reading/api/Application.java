package com.code.resource.reading.api;



/**
 * Application
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/04/20
 * <p>
 * desc：
 */
/**@SpringBootApplication(scanBasePackages = {"com.code.resource.reading.api"})
@EnableDubbo
public class Application {
    public static void main(String[] args) {
        ConfigurableApplicationContext application = SpringApplication.run(Application.class, args);
        ConfigurableEnvironment environment = application.getEnvironment();


    }
}
*/