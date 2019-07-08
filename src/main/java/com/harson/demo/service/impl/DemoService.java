package com.harson.demo.service.impl;

import com.harson.demo.service.IDemoService;
import com.harson.framework.annotation.MyService;

@MyService
public class DemoService implements IDemoService {
    public String get(String name) {
        System.out.println("调用了service层的get方法！");
        return name +"'s value is ...";
    }
}
