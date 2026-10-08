package com.yizhaoqi.intellidoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IntelliDocApplication {

    public static void main(String[] args) {
        // 禁用 Netty 原生 DNS 解析器，改用 JVM 默认 DNS，避免某些网络环境下 UDP DNS 超时
        System.setProperty("reactor.netty.dns.native", "false");
        SpringApplication.run(IntelliDocApplication.class, args);
    }

}
