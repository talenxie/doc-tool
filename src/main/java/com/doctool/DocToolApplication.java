package com.doctool;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

@SpringBootApplication
@MapperScan("com.doctool.mapper")
public class DocToolApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(DocToolApplication.class, args);
    }

    // 新增这个重写方法，仅此一处
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(DocToolApplication.class);
    }
}
