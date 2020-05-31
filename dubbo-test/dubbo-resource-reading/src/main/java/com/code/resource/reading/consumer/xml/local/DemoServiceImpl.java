package com.code.resource.reading.consumer.xml.local;

import com.code.resource.reading.api.DemoService;

/**
 * DemoServiceImpl
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/05/29
 * <p>
 * desc：
 */
public class DemoServiceImpl implements DemoService {
    @Override
    public String hello() {
        return "lalala";
    }
}
