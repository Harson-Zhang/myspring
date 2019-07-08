package com.harson.framework.servlet;

import com.harson.framework.annotation.*;
import com.harson.demo.service.IDemoService;

import org.apache.log4j.Logger;
import org.omg.PortableInterceptor.LOCATION_FORWARD;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@MyController
@MyRequestMapping("/demo")
public class MyDispatcherServlet extends HttpServlet {
    private static final long serialVersionID = 1L;
    public static final Logger log = Logger.getLogger(MyDispatcherServlet.class.getName());

    //web.xml中的名字保持一致
    private static final String LOCATION = "contextConfigLocation";
    //保存配置信息
    private Properties contextConfig = new Properties();
    //保存所有被扫描到的相关类名
    private List<String> classNames = new ArrayList<String>();
    //IOC,保存所有初始化的Bean
    private Map<String, Object> ioc = new HashMap<>();
    //保存URL到方法的映射
    private Map<String, Method> handlerMapping = new HashMap<>();

    public MyDispatcherServlet() {
        super();
    }

    // 初始化工作
    // 有疑问，真的会初始化吗
    public void init(ServletConfig config) {
        log.info("SpringMVC初始化");

        //1. 加载配置文件
        doLoadConfig(config.getInitParameter(LOCATION));

        //2. 扫描相关类
        doScanner(contextConfig.getProperty("scanPackage"));

        //3. 初始化IoC容器，相关Bean放入IoC容器中
        doInstance();

        //4. 实现依赖注入/DI操作，给没有赋值的属性赋值
        doAutowired();

        //5. 构造handlerMapping
        initHandlerMapping();

        System.out.println("My Spring framwork has initialized!");

    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws javax.servlet.ServletException, IOException {
        doGet(request, response);
    }

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws javax.servlet.ServletException, IOException {
        try {
            doDispatch(request, response);
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    private void doLoadConfig(String contextConfigLocation) {
        //1. 加载配置文件到contextConfig
        InputStream is = null;
        try {
            is = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
            contextConfig.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    //2. 扫描相关类，类名加到classNames中
    private void doScanner(String scanPackage) {
        //把contextConfig加载的包名转换为文件路径
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));
        File classPath = new File(url.getFile());
        //将扫描到的所有类加载到List里
        for (File file : classPath.listFiles()) {
            //扫描到文件夹, 就往深层扫
            if (file.isDirectory()) {
                doScanner(scanPackage + "." + file.getName());
            } else {
                if (file.getName().endsWith(".class")) {
                    continue;
                }
                String className = (scanPackage + "." + file.getName()).replace(".class", "");
                classNames.add(className);
            }
        }
    }

    //3. 初始化IoC容器，相关的Bean放入IoC（Map<String, Object>）
    private void doInstance() {
        if (classNames.isEmpty()) {
            return;
        }
        try {
            //根据className,反射生成每一个实例对象
            for (String className : classNames) {
                Class<?> clazz = Class.forName(className);
                Object object = clazz.newInstance();
                String simpleName = toLowerFirstCase(clazz.getSimpleName()); //纯正Spring的IOC容器的key默认是类名首字母小写

                if (clazz.isAnnotationPresent(MyController.class)) { //Controller 没涉及接口的实现，也没有参数，分出来写
                    ioc.put(simpleName, object);
                } else if (clazz.isAnnotationPresent(MyService.class)) {
                    MyService service = clazz.getAnnotation(MyService.class);
                    if (!"".equals(service.value().trim())) {
                        //特殊情况：有自定义的Service注解参数
                        simpleName = service.value();
                    }
                    ioc.put(simpleName, object);

                    // 实现多个接口的情况和特殊：一个接口被多个类实现的情况
                    for (Class<?> ele : clazz.getInterfaces()) {
                        if (ioc.containsKey(ele.getName())) {
                            throw new Exception("the interface has already exsited");
                        }
                        ioc.put(ele.getName(), object);
                    }
                } else {
                    continue;
                }


            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //工具：将首字母的大写转为小写
    private String toLowerFirstCase(String str) {
        char[] chars = str.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }

    //4. 依赖注入，将ioc容器的实例注入到相应对象中
    private void doAutowired() {
        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            //拿到实例对象中的所有属性，
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {
                if (!field.isAnnotationPresent(MyAutowired.class)) {
                    continue;
                } //Autowired注解必须有, 才初始化属性

                MyAutowired autowired = field.getAnnotation(MyAutowired.class); //看看autowired有没有自定义
                String beanName = autowired.value().trim();
                if ("".equals(beanName)) { //没有自定义value，直接获取类名（请调试）
                    beanName = field.getType().getName();
                }
                field.setAccessible(true);
                try {
                    field.set(entry.getValue(), ioc.get(beanName)); //往字段里填充新数值
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                    continue;
                }
            }

        }
    }

    //5. 构造handlerMapping，建立method与url的一一对应关系
    private void initHandlerMapping() {
        if (ioc.isEmpty()) return;

        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            if (clazz.isAnnotationPresent(MyController.class)) continue; //确保是@Controller的类

            String baseUrl = ""; //完整URL = Controller对象Url + 方法Url
            //获取Controller对象Url
            if (clazz.isAnnotationPresent(MyRequestMapping.class)) {
                MyRequestMapping requestMapping = clazz.getAnnotation(MyRequestMapping.class);
                baseUrl = requestMapping.value();
            }

            //获取Method方法Url
            for (Method method : clazz.getMethods()) {
                //没有@RequestMapping的直接忽略
                if (!method.isAnnotationPresent(MyRequestMapping.class)) continue;

                //映射Url
                MyRequestMapping mapping = method.getAnnotation(MyRequestMapping.class);
                String url = ("/" + baseUrl + "/" + mapping.value()).replaceAll("/+", "/");
                handlerMapping.put(url, method);
                System.out.println("mapped " + url + "," + method);
            }
        }
    }

    private void doDispatch(HttpServletRequest request, HttpServletResponse response) throws IOException, InvocationTargetException, IllegalAccessException {
        //6. 获取URI(URL将会带有http)
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String url = uri.replaceAll(contextPath, "").replaceAll("/+", "/");

        //7. HandlerMapping中查找对应Method
        if (!this.handlerMapping.containsKey(url)) {
            response.getWriter().write("<h1>404 NOT FOUND</h1>");
            return;
        }
        Method method = this.handlerMapping.get(url);

        //8. 反射取出Method对应方法，执行并得到返回结果
        Map<String, String[]> params = request.getParameterMap();

        //获取方法参数类型 的列表
        Class<?>[] parameterTypes = method.getParameterTypes();
        //获取请求的参数
        String beanName = toLowerFirstCase((method.getDeclaringClass().getSimpleName()));


        //保存参数值
        Object[] paramValues = new Object[parameterTypes.length];
        for (int i = 0; i < paramValues.length; i++) {
            //根据参数名称做处理
            Class parameterType = parameterTypes[i];
            if (parameterType == HttpServletRequest.class) {
                paramValues[i] = request;
                continue;
            } else if (parameterType == HttpServletResponse.class) {
                paramValues[i] = response;
                continue;
            } else if (parameterType == String.class) {
                for (Map.Entry<String, String[]> param : params.entrySet()) {
                    String value = Arrays.toString(param.getValue())
                            .replaceAll("\\[|\\]", "")
                            .replaceAll(",\\s", ",");
                    paramValues[i] = value;
                }
            }
        }

        method.invoke(ioc.get(beanName), paramValues);

        //9. 将返回结果传给response
        //并不会。。。
    }

    //依赖注入
    @MyAutowired
    private IDemoService demoService;

    // 查找
    @MyRequestMapping("/query.json")
    public void query(HttpServletRequest request, HttpServletResponse response, @MyRequestParam("name") String name) throws IOException {
        String result = demoService.get(name);
        response.getWriter().write(result);
    }

    //增加
    @MyRequestMapping("/add.json")
    public void add(HttpServletRequest request, HttpServletResponse response, @MyRequestParam("a") Integer a, @MyRequestParam("b") Integer b) throws IOException {
        response.getWriter().write(a + " + " + b + " = " + (a + b));
    }

    //删除
    @MyRequestMapping("/remove.json")
    public void remove(HttpServletRequest request, HttpServletResponse response, @MyRequestParam("id") Integer id) {

    }

}
