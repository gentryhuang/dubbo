package com.code.reading.service;

import org.apache.dubbo.config.annotation.Service;

/**
 * DemoServiceImpl
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/05/18
 * <p>
 * desc：
 */
@Service(timeout = 500000000)
public class DemoServiceImpl implements IDemoService {

    @Override
    public String ping(String pg) {
        return pg + " is running";
    }
}
