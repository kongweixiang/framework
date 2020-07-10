#Dubbo自适应扩展机制
  Dubbo设计时采用Microkernel + Plugin模式，Microkernel只负责组装Plugin，Dubbo自身的功能也是通过扩展点实现的，也就是Dubbo的所有功能点都可被用户自定义扩展所替换。 Dubbo 就是通过 SPI 机制加载所有的组件，不过，Dubbo 并未使用 Java 原生的 SPI 机制，而是对其进行了增强，使其能够更好的满足需求  
  SPI 全称为 Service Provider Interface，是一种服务发现机制。SPI 的本质是将接口实现类的全限定名配置在文件中，并由服务加载器读取配置文件，加载实现类。这样可以在运行时，动态为接口替换实现类。

  ### Dubbo SPI
  与 Java SPI 实现类配置不同，Dubbo SPI 是通过键值对的方式进行配置，这样我们可以按需加载指定的实现类，而传统的 Java SPI 是通过服务类class进行加载的。  
  Dubbo加载服务类为`ExtensionLoader`（加载拓展类），我更愿意称呼他为拓展加载器,通过这个类我们可以获取到拓展类对应的 ExtensionLoader，然后通过name获取指定服务。  
  首先我们看一下ExtensionLoader的设计思路：  
  1. 首先加载拓展类被设计成单例模式的，每个方法都是static的，拓展类对应ExtensionLoader（加载拓展类）map，推展类对应服务对象map管理器，拓展服务加载策略LoadingStrategy都是全局共享的。
  2. 每个类型的拓展类都会有一个拓展加载器，通过一个map(EXTENSION_LOADERS)管理所有类型的拓展加载器,这个map是静态全局唯一。
  3. 每个类加都可以设置一个默认名字的服务，当传入name传入“true”，获取默认服务`(@SPI 设置默认服务名)`。
  4. 缓存加载(cachedInstances、cachedActivates，cachedInstances，cachedWrapperClasses)。
  5. 扩展加载器工厂类SpiExtensionFactory(加载@SPI注解过的服务)、AdaptiveExtensionFactory（普通的服务）。
  6. 加载策略，每次加载对象事从下面四个策略目录下加载服务配置，加载优先级按下面顺序排列。
       - DubboInternalLoadingStrategy（目录META-INF/dubbo/internal/下）
       - DubboExternalLoadingStrategy（目录META-INF/dubbo/external/下）
       - DubboLoadingStrategy（目录META-INF/dubbo/下）
       - ServicesLoadingStrategy（目录META-INF/services/下）
  6. 加载自适应的服务进行代理。
   #### 基础使用
  Dubbo SPI 使用示例:  
  在 META-INF/dubbo下配置
  ```java
    # Comment 1
    impl1=org.apache.dubbo.common.extension.ext1.impl.SimpleExtImpl1#Hello World
    impl2=org.apache.dubbo.common.extension.ext1.impl.SimpleExtImpl2  # Comment 2
    impl3=org.apache.dubbo.common.extension.ext1.impl.SimpleExtImpl3 # with head space
```
使用ExtensionLoader对象获取SPI服务
  ```java
        SimpleExt ext = ExtensionLoader.getExtensionLoader(SimpleExt.class).getExtension("impl1");
   ```

  #### 源码
   Dubbo SPI 实现源码：在获取spi服务时首先获取指定类型(interface的class)的服务的扩展加载器。
   ```java
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
            if (type == null) {
                throw new IllegalArgumentException("Extension type == null");
            }
            if (!type.isInterface()) {
                throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
            }
            if (!withExtensionAnnotation(type)) {
                throw new IllegalArgumentException("Extension type (" + type +
                        ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
            }
            //获取当前类的扩展加载器
            ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type); //从缓存中获取指定class的扩展加载器
            if (loader == null) { //如果缓存中不存在指定类的扩展加载器，则初始化放入内存
                EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
                loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
            }
            return loader;
        }
  ```
在我们创建新的扩展加载器时，会进行服务的加载以及服务的初始化。  
*ps:第一调用getExtensionLoader方法时会进行ExtensionFactory扩展加载。*
```java
private ExtensionLoader(Class<?> type) {
        this.type = type;
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
        /** 
            注意服务内存第一次调用getExtensionLoader时这里的递归调用，会有两次new ExtensionLoader<T>(type)操作，第一次为type触发，进入后
            ExtensionLoader.getExtensionLoader(ExtensionFactory.class)获取ExtensionFactory时会再次触发new ExtensionLoader<T>(type)，
            然后type == ExtensionFactory.class返回到ExtensionFactory的ExtensionLoader进行getAdaptiveExtension()装配ExtensionFactory服务。
            然后再将装配好的ExtensionFactory返回给上一次调用的type的扩展加载器的objectFactory，用这个objectFactory进行type的扩展装配。
        */
    }
```
获取到扩展加载器后进行自适应扩展

```java
public T getAdaptiveExtension() {
        Object instance = cachedAdaptiveInstance.get(); //先从缓存中获取扩展适配对象
        if (instance == null) {
            if (createAdaptiveInstanceError != null) {
                throw new IllegalStateException("Failed to create adaptive instance: " +
                        createAdaptiveInstanceError.toString(),
                        createAdaptiveInstanceError);
            }
            //缓存中未获取到扩展适配对象，则进行扩展加载
            synchronized (cachedAdaptiveInstance) {
                instance = cachedAdaptiveInstance.get();
                if (instance == null) { //双重锁
                    try {
                        instance = createAdaptiveExtension();//创建自适应扩展对象
                        cachedAdaptiveInstance.set(instance); // 加载到的扩展适配对象写入缓存
                    } catch (Throwable t) {
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        }

        return (T) instance;
    }
```

**创建自适应扩展对象**

```java
private T createAdaptiveExtension() {
        try {
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }
```

加载时先获取`getAdaptiveExtensionClass`首先加载服务，再将服务信息写入缓存，选出自适应扩展对象进行`newInstance()`实例化，在进行实例依赖注入`injectExtension(T instance)`

首先我们看一下创建自适应扩展对象createAdaptiveExtension()
```java
private Class<?> getAdaptiveExtensionClass() {
        getExtensionClasses(); //加载扩展配置服务
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        return cachedAdaptiveClass = createAdaptiveExtensionClass(); //创建自适应扩展
    }

private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    classes = loadExtensionClasses(); //加载扩展配置服务启动
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

private Map<String, Class<?>> loadExtensionClasses() {
        cacheDefaultExtensionName(); //通过SPI注解获取type上的默认服务

        Map<String, Class<?>> extensionClasses = new HashMap<>();

        for (LoadingStrategy strategy : strategies) { //按照加载策略从目录中获取所有的扩展配置服务
            loadDirectory(extensionClasses, strategy.directory(), type.getName(), strategy.preferExtensionClassLoader(), strategy.overridden(), strategy.excludedPackages());
            loadDirectory(extensionClasses, strategy.directory(), type.getName().replace("org.apache", "com.alibaba"), strategy.preferExtensionClassLoader(), strategy.overridden(), strategy.excludedPackages());
        }

        return extensionClasses;
    }

//从目前中查找服务并加载进来
String fileName = dir + type;
        try {
            Enumeration<java.net.URL> urls = null;
            ClassLoader classLoader = findClassLoader();

            // try to load from ExtensionLoader's ClassLoader first
            if (extensionLoaderClassLoaderFirst) {
                ClassLoader extensionLoaderClassLoader = ExtensionLoader.class.getClassLoader();
                if (ClassLoader.getSystemClassLoader() != extensionLoaderClassLoader) {
                    urls = extensionLoaderClassLoader.getResources(fileName);
                }
            }

            if (urls == null || !urls.hasMoreElements()) {
                if (classLoader != null) {
                    urls = classLoader.getResources(fileName);
                } else {
                    urls = ClassLoader.getSystemResources(fileName);
                }
            }

            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    loadResource(extensionClasses, classLoader, resourceURL, overridden, excludedPackages); //加载资源
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }
    

```
在加载资源的时候进行class类的生成

```java
private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name,
                           boolean overridden) throws NoSuchMethodException {
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error occurred when loading extension class (interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + " is not subtype of interface.");
        }
        if (clazz.isAnnotationPresent(Adaptive.class)) { //适配对象服务
            cacheAdaptiveClass(clazz, overridden);  //将适配服务记录在cachedAdaptiveClass成员变量中
        } else if (isWrapperClass(clazz)) {
            cacheWrapperClass(clazz); // 将包装器类对象记入cachedWrapperClasses成员变量中
        } else { //普通服务加载
            clazz.getConstructor();
            if (StringUtils.isEmpty(name)) {
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {
                    cacheName(clazz, n); //写入cachedNames成员变量中
                    saveInExtensionClass(extensionClasses, clazz, n, overridden);//保存入extensionClasses成员变量中
                }
            }
        }
    }
```
  到这儿整个扩展服务对象加载完成，加载的服务还未进行实例化，通过Class.newInstance()进行实例化，在通过injectExtension将实例化进行服务注入（如果对象引用了其他服务）
  
  这样我们就完成了整个扩展加载器的初始化工作，在获得我们指定类型服务的扩展加载器后，我们就可以通过指定名称获取到的具体服务对象实例。
```java
//获取指定名字的服务
 public T getExtension(String name) {
        return getExtension(name, true);
    }

//获取指定名字的服务对象，获取对象是否需要包装
public T getExtension(String name, boolean wrap) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        if ("true".equals(name)) {
            return getDefaultExtension();
        }
        final Holder<Object> holder = getOrCreateHolder(name); //持有服务，服务从缓存中获取
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) { //指定的服务不存在
                    instance = createExtension(name, wrap); //重新初始化EXTENSION_INSTANCES中的对象，如果扩展服务加载器中有包装对象，对服务进行包装和生命周期Lifecycle的初始化
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }
```
通过`getExtension`我们就可以获取到我们想要的自适应服务了，如果不存在则抛出IllegalStateException异常。


## 自适应扩展
  自适应拓展类的核心实现 —— 在拓展接口的方法被调用时，通过 SPI 加载具体的拓展实现类，并调用拓展对象的同名方法。  
  自适应拓展相关的注解 @Adaptive 。在Dubbo中，仅有两个类被Adaptive注解了，分别是 AdaptiveCompiler 和 AdaptiveExtensionFactory。此种情况，表示拓展的加载逻辑由人工编码完成。更多时候，Adaptive 是注解在接口方法上的，表示拓展的加载逻辑需由框架自动生成。Adaptive 注解的地方不同，相应的处理逻辑也是不同的。  
   获取自适应拓展方法getAdaptiveExtension()我们已经在上面大体介绍过了，现在说一下其中的createAdaptiveExtensionClass
   ```java
 private Class<?> createAdaptiveExtensionClass() {
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate(); //通过自适应代码编辑生成器生成
        ClassLoader classLoader = findClassLoader();
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        return compiler.compile(code, classLoader); // 默认使用javassist 汇编对象class
    }

    public String generate() {
        // no need to generate adaptive class since there's no adaptive method found.
        if (!hasAdaptiveMethod()) {
            throw new IllegalStateException("No adaptive method exist on extension " + type.getName() + ", refuse to create the adaptive class!");
        }

        StringBuilder code = new StringBuilder();
        code.append(generatePackageInfo()); //生成包信息
        code.append(generateImports()); //生成import信息
        code.append(generateClassDeclaration()); 生成类隐入信息

        Method[] methods = type.getMethods();
        for (Method method : methods) {
            code.append(generateMethod(method)); // 生成方法，Dubbo 不会为没有标注 Adaptive 注解的方法生成代理逻辑
        }
        code.append("}");

        if (logger.isDebugEnabled()) {
            logger.debug(code.toString());
        }
        return code.toString();
    }
```
Bubbo 自适应代码编辑生成器生成代码是个和复杂的过程，其中生成方法更为复杂：例如
```java
for (Method method : methods) {
    Class<?> rt = method.getReturnType();
    Class<?>[] pts = method.getParameterTypes();
    Class<?>[] ets = method.getExceptionTypes();

    Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
    StringBuilder code = new StringBuilder(512);
    if (adaptiveAnnotation == null) {
        // $无 Adaptive 注解方法代码生成逻辑}
    } else {
        // ${获取 URL 数据}
        
        // ${获取 Adaptive 注解值}
        
        // ${检测 Invocation 参数}
        
        // ${生成拓展名获取逻辑}
        
        // ${生成拓展加载与目标方法调用逻辑}
    }
}
    
// public + 返回值全限定名 + 方法名 + (
codeBuilder.append("\npublic ")
    .append(rt.getCanonicalName())
    .append(" ")
    .append(method.getName())
    .append("(");

// 添加参数列表代码
for (int i = 0; i < pts.length; i++) {
    if (i > 0) {
        codeBuilder.append(", ");
    }
    codeBuilder.append(pts[i].getCanonicalName());
    codeBuilder.append(" ");
    codeBuilder.append("arg").append(i);
}
codeBuilder.append(")");

// 添加异常抛出代码
if (ets.length > 0) {
    codeBuilder.append(" throws ");
    for (int i = 0; i < ets.length; i++) {
        if (i > 0) {
            codeBuilder.append(", ");
        }
        codeBuilder.append(ets[i].getCanonicalName());
    }
}
codeBuilder.append(" {");
codeBuilder.append(code.toString());
codeBuilder.append("\n}");
```
这里就不详细介绍了，需要了解的同学去Dubbo官网学习。