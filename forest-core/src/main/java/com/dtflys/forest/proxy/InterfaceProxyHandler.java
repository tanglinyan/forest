package com.dtflys.forest.proxy;

import com.dtflys.forest.annotation.BaseLifeCycle;
import com.dtflys.forest.annotation.BaseRequest;
import com.dtflys.forest.annotation.BaseURL;
import com.dtflys.forest.annotation.MethodLifeCycle;
import com.dtflys.forest.config.ForestConfiguration;
import com.dtflys.forest.config.VariableScope;
import com.dtflys.forest.exceptions.ForestRuntimeException;
import com.dtflys.forest.interceptor.Interceptor;
import com.dtflys.forest.interceptor.InterceptorFactory;
import com.dtflys.forest.lifecycles.BaseAnnotationLifeCycle;
import com.dtflys.forest.logging.ForestLogHandler;
import com.dtflys.forest.logging.LogConfiguration;
import com.dtflys.forest.mapping.MappingTemplate;
import com.dtflys.forest.mapping.MappingVariable;
import com.dtflys.forest.reflection.ForestMethod;
import com.dtflys.forest.reflection.MetaRequest;
import com.dtflys.forest.utils.ReflectUtils;
import com.dtflys.forest.utils.StringUtils;
import com.dtflys.forest.utils.URLUtils;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author gongjun[dt_flys@hotmail.com]
 * @since 2016-05-04
 */
public class InterfaceProxyHandler<T> implements InvocationHandler, VariableScope {

    private ForestConfiguration configuration;

    private ProxyFactory proxyFactory;

    private Class<T> interfaceClass;

    private Map<Method, ForestMethod> forestMethodMap = new HashMap<Method, ForestMethod>();

    private MetaRequest baseMetaRequest = new MetaRequest();

    private InterceptorFactory interceptorFactory;

    private String baseURL;

    private LogConfiguration baseLogConfiguration;

    private final Constructor<MethodHandles.Lookup> defaultMethodConstructor;

    private MethodHandles.Lookup defaultMethodLookup;


    private List<Annotation> baseAnnotations = new LinkedList<>();


    public ProxyFactory getProxyFactory() {
        return proxyFactory;
    }

    public InterfaceProxyHandler(ForestConfiguration configuration, ProxyFactory proxyFactory, Class<T> interfaceClass) {
        this.configuration = configuration;
        this.proxyFactory = proxyFactory;
        this.interfaceClass = interfaceClass;
        this.interceptorFactory = configuration.getInterceptorFactory();

        try {
            defaultMethodConstructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
            if (!defaultMethodConstructor.isAccessible()) {
                defaultMethodConstructor.setAccessible(true);
            }
            defaultMethodLookup = defaultMethodConstructor.newInstance(interfaceClass, MethodHandles.Lookup.PRIVATE);
        } catch (Throwable e) {
            throw new ForestRuntimeException(e);
        }
        prepareBaseInfo();
        initMethods();
    }


    private void prepareBaseInfo() {
        Annotation[] annotations = interfaceClass.getAnnotations();

        for (int i = 0; i < annotations.length; i++) {
            Annotation annotation = annotations[i];
            if (annotation instanceof BaseURL) {
                BaseURL baseURLAnn = (BaseURL) annotation;
                String value = baseURLAnn.value();
                if (value == null || value.trim().length() == 0) {
                    continue;
                }
                baseURL = value.trim();
                baseMetaRequest.setUrl(baseURL);
            } else {
                BaseLifeCycle baseLifeCycle = annotation.annotationType().getAnnotation(BaseLifeCycle.class);
                MethodLifeCycle methodLifeCycle = annotation.annotationType().getAnnotation(MethodLifeCycle.class);
                if (baseLifeCycle != null || methodLifeCycle != null) {
                    if (baseLifeCycle != null) {
                        Class<? extends BaseAnnotationLifeCycle> interceptorClass = baseLifeCycle.value();
                        if (interceptorClass != null) {
                            BaseAnnotationLifeCycle baseInterceptor = interceptorFactory.getInterceptor(interceptorClass);
                            baseInterceptor.onProxyHandlerInitialized(this, annotation);
                        }
                    }
                    baseAnnotations.add(annotation);
                }
            }
        }
    }


    private void initMethods() {
        Method[] methods = interfaceClass.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            if(method.isDefault()){
                continue;
            }
            ForestMethod forestMethod = new ForestMethod(this, configuration, method);
            forestMethodMap.put(method, forestMethod);
        }
    }

    /**
     * 调用 Forest 动态代理接口对象的方法
     *
     * @param proxy 动态代理对象
     * @param method 所要调用的方法 {@link Method}对象
     * @param args 所要调用方法的入参数组
     * @return 方法调用返回结果
     * @throws Throwable 方法调用过程中可能抛出的异常
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        if (method.isDefault()) {
          return invokeDefaultMethod(proxy, method, args);
        }
        ForestMethod forestMethod = forestMethodMap.get(method);
        if (forestMethod == null) {
            if (args == null || args.length == 0) {
                if ("toString".equals(methodName)) {
                    return "{Forest Proxy Object of " + interfaceClass.getName() + "}";
                }
                if ("getClass".equals(methodName)) {
                    return proxy.getClass();
                }
                if ("hashCode".equals(methodName)) {
                    return proxy.hashCode();
                }
                if ("notify".equals(methodName)) {
                    proxy.notify();
                    return null;
                }
                if ("notifyAll".equals(methodName)) {
                    proxy.notifyAll();
                    return null;
                }
                if ("wait".equals(methodName)) {
                    proxy.wait();
                    return null;
                }
            }
            if (args != null && args.length == 1) {
                if ("equals".equals(methodName)) {
                    Object obj = args[0];
                    if (Proxy.isProxyClass(obj.getClass())) {
                        InvocationHandler h1 = Proxy.getInvocationHandler(proxy);
                        InvocationHandler h2 = Proxy.getInvocationHandler(obj);
                        return h1.equals(h2);
                    }
                    return false;
                }
                if ("wait".equals(methodName) && args[0] instanceof Long) {
                    proxy.wait((Long) args[0]);
                }
            }
            if (args != null && args.length == 2 &&
                    args[0] instanceof Long &&
                    args[1] instanceof Integer) {
                if ("wait".equals(methodName)) {
                    proxy.wait((Long) args[0], (Integer) args[1]);
                }
            }
            throw new NoSuchMethodError(method.getName());
        }
        return forestMethod.invoke(args);
    }

  private Object invokeDefaultMethod(Object proxy, Method method, Object[] args)
          throws Throwable {
    return defaultMethodLookup.unreflectSpecial(method, interfaceClass)
            .bindTo(proxy).invokeWithArguments(args);
  }

    public MetaRequest getBaseMetaRequest() {
        return baseMetaRequest;
    }

    @Override
    public boolean isVariableDefined(String name) {
        return configuration.isVariableDefined(name);
    }

    @Override
    public Object getVariableValue(String name) {
        return getVariableValue(name, null);
    }

    @Override
    public Object getVariableValue(String name, ForestMethod method) {
        return configuration.getVariableValue(name, method);
    }

    public List<Annotation> getBaseAnnotations() {
        return baseAnnotations;
    }

    @Override
    public MappingVariable getVariable(String name) {
        return null;
    }

    @Override
    public ForestConfiguration getConfiguration() {
        return configuration;
    }

    public LogConfiguration getBaseLogConfiguration() {
        return baseLogConfiguration;
    }

    public void setBaseLogConfiguration(LogConfiguration baseLogConfiguration) {
        this.baseLogConfiguration = baseLogConfiguration;
    }
}
