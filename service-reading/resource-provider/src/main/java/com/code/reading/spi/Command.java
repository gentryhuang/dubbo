package com.code.reading.spi;

import org.apache.dubbo.common.extension.SPI;

/**
 * Command
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/08/10
 * <p>
 * desc：
 */
@SPI("start")
public interface Command {

    public void execute();
}
