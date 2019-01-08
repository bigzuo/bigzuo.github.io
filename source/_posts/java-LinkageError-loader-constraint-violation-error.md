---
title: Java LinkageError:loader constraint violation 异常分析与解决
date: 2017-03-19 12:07:49
categories: Java/JVM
---

> 原创文章，如需转载，请注明来自：<https://bigzuo.github.io/>

## 环境信息
Tomcat 7，JDK1.7， Windows 7

## 问题现象
最近一个很久没有运行的程序启动时，出现`java.lang.LinkageError`异常，查了一下，是因为在应用程序的lib目录下和tomcat的lib目录下有相同的**slf4j-api-1.7.7.jar** jar包导致的。果然，删除掉tomcat lib目录下对应的jar包后，应用程序启动正常。不过，为什么不是删除应用程序lib目录下重复的jar包呢？因为这Tomcat个jar包不是Tomcat lib目录下自带的jar包，是开发人员添加进去的。为了避免以后再出现这个问题，所以要删除Tomcat lib目录下的重复jar包，深入原因见下文。  

<!-- more -->  

```java
nested exception is java.lang.LinkageError: loader constraint violation: when
resolving method "org.slf4j.impl.StaticLoggerBinder.getLoggerFactory()Lorg/slf4j/ILoggerFactory;" the class loader (instance of org/apache/catalina/loader/WebappClassLoader) of the current class, org/
slf4j/LoggerFactory, and the class loader (instance of org/apache/catalina/loader/StandardClassLoader) for resolved class, org/slf4j/impl/StaticLoggerBinder, have different Class objects for the type
org/slf4j/ILoggerFactory used in the signature
        at org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor.postProcessPropertyValues(AutowiredAnnotationBeanPostProcessor.java:287)
        at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.populateBean(AbstractAutowireCapableBeanFactory.java:1106)
        at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.doCreateBean(AbstractAutowireCapableBeanFactory.java:517)
        at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBean(AbstractAutowireCapableBeanFactory.java:456)
        at org.springframework.beans.factory.support.AbstractBeanFactory$1.getObject(AbstractBeanFactory.java:294)
        at org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.getSingleton(DefaultSingletonBeanRegistry.java:225)
        at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:291)
        at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:193)
        at org.springframework.beans.factory.support.DefaultListableBeanFactory.preInstantiateSingletons(DefaultListableBeanFactory.java:585)
        at org.springframework.context.support.AbstractApplicationContext.finishBeanFactoryInitialization(AbstractApplicationContext.java:913)
        at org.springframework.context.support.AbstractApplicationContext.refresh(AbstractApplicationContext.java:464)
        at org.springframework.web.servlet.FrameworkServlet.configureAndRefreshWebApplicationContext(FrameworkServlet.java:631)
        at org.springframework.web.servlet.FrameworkServlet.createWebApplicationContext(FrameworkServlet.java:588)
```  
## 原因分析
为什么Tomcat lib目录和应用程序lib目录下有相同的jar包会出现“**LinkageError**”异常呢？虽然自己已经知道JVM的类加载机制及双亲委派原则，但是还是不清楚为什么会现这个问题。下面我们就一步步分析“**LinkageError**”异常产生的原因及Tomcat和JVM类加载机制的区别。

### 这是什么错？
在JVM中，是根据一个类的全类名和这个类对应的类加载器来唯一确定一个类实例的。当同一个类被不同类加载器加载时，在JVM中就是两个完全不同的类实例。因此，当同一个类文件被不同类加载器加载到内存中生成的类实例发生交互时，就会出现“**LinkageError**”异常。  
在此，我们可以继续分析一下异常日志的内容：  
`when
resolving method "org.slf4j.impl.StaticLoggerBinder.getLoggerFactory()Lorg/slf4j/ILoggerFactory;" the class loader (instance of org/apache/catalina/loader/WebappClassLoader) of the current class, org/slf4j/LoggerFactory`
当解析`"org.slf4j.impl.StaticLoggerBinder.getLoggerFactory()Lorg/slf4j/ILoggerFactory;"`方法时，类`org/slf4j/LoggerFactory`的加载器是`instance of org/apache/catalina/loader/WebappClassLoader`，但是类` org/slf4j/impl/StaticLoggerBinder`的类加载器是`instance of org/apache/catalina/loader/StandardClassLoader`，因此分别被两个类加载器加载的类`org/slf4j/LoggerFactory`发生交互，所以JVM抛出“**LinkageError**”异常。

### Tomcat和JVM类加载机制并不一样！
虽然这个问题很快解决，但是自己不能理解为什么会有两个不同的类加载器去加载同一个类，因为自己的印象是**一般情况下，类加载器会遵守双亲委派的原则**，当类加载器需要加载一个类时，会先将加载任务委托给自己的父类加载器去加载，只有在父类加载器加载失败的情况下，才会自己加载。那么既然是双亲委派机制，就不应该出现同一个类被不同类加载器加载的情况啊。直到后来发现，Tomcat和JVM类加载机制并不一样，才明白问题的原因。  
#### JVM 类加载机制
JVM类加载器遵守双亲委派的原则，即除启动类加载器**Bootstrap ClassLoader**外，其他所有类加载器都继承自其他类加载器，也就是都有一个父类。当一个类加载器需要加载类时，会首先委派给自己的父类加载，只有当父类加载不到时，才会自己加载。
![](https://bigzuo.coding.me/bigzuo/images/JVM_classLoader.png)  
使用双亲委派模型的一个好处是，Java类随着它的类加载器一起具备了一种带有优先级的层次关系。比如“**java.lang.Object**”类，它存放在“**rt.jar**”包中，因此如论程序运行的什么环境需要使用“**java.lang.Object**”类，最终都会委托给启动类加载器**Bootstrap ClassLoader**去加载，因此程序运行时始终使用的是同一个“**java.lang.Object**”类。相反，如果不遵守双亲委派原则，用户自己也可以自定义一个“**java.lang.Object**”类放到**ClassPath**路径下，这样也会被加载，因此应用程序中会出现多个不同的“**java.lang.Object**”类，Java类型体系中最基础的行为也无法保证。  
类加载的双亲委派原则虽然可以保证应用程序运行的稳定性，但是却不能很好的支持灵活扩展，比如当一个应用服务器部署多个应用时，如果两个应用引用了一个不同版本的jar包，那么其中一个应用引用的jar包也能就无法正确加载。因此，Tomcat就打破了双亲委派原则。
#### Tomcat 类加载机制
和其他的应用服务器一样，Tomcat也安装了多种类加载器（即实现了`java.lang.ClassLoader`的类）以实现容器的不同部分、运行在容器上的不同应用程序可以访问不同的资源库。因此，Tomcat上的web应用程序类加载机制和JVM的双亲委派机制有所不同。  
Tomcat启动时，会创建一系列类加载器，这些类加载器会安装如下图的父子关系组织，父加载器在子加载器之上。

<div class="codeBox"><pre><code>      Bootstrap
          |
       System
          |
       Common
       /     \
  Webapp1   Webapp2 ...</code></pre></div>

如上图所示，Tomcat在初始化时会创建如下类加载器： 
 
- **Bootstrap**加载器，用于加载JVM提供的基本的运行时类以及系统扩展目录(`$JAVA_HOME/jre/lib/ext`)下的jar包中的类。
- **System**加载器，用于初始化`CLASSPATH `环境变量下面的内容。
- **Common**加载器，用于加载一些额外的类，比如有些应用会把连接数据库相关的类放到这个目录。正常情况下，**应用程序的类不应该放到这个目录**。这也解释了刚才提到的，为什么是删除掉Tomcat lib目录下对应的重复jar包。
- **WebappX**加载器，用于加载部署在同一个Tomcat实例上不同应用程序，包括应用程序`/WEB-INF/classes`目录下所有解压的类和资源文件，` /WEB-INF/lib`目录下所有的jar包中的类和额外的类。这些都是对应用程序可见的，但是对其他的应用程序却是不可见的。

如上文提到，web应用程序类加载机制和默认的JVM双亲委派模型稍有不同。当**WebappX**加载器收到类加载的请求时，和首先委托给父类加载器进行加载不同，**WebappX**加载器会首先在自己本地资源库中查找类。不过也有例外，**JRE基类不会被重写**。最后，对于JavaEE API这部分类，Tomcat对**WebappX**加载器有特定的实现，即首先委托给父类加载器加载。Tomcat其他的类加载器依然遵守传统的双亲委派加载机制。  

读到这里，大家就应该可以很明显发现问题所在： 
 
- Tomcat启动时，**Common**加载器会加载`$CATALINA_HOME/lib`和`$CATALINA_BASE/lib`目录下的**slf4j-api-1.7.7.jar** jar包中的类，其中包括`org/slf4j/LoggerFactory`类。日志中显示的类加载器是`instance of org/apache/catalina/loader/StandardClassLoader`，因为`CommonLoader`是`StandardClassLoader`的子类。
- **WebappX**加载器会加载` /WEB-INF/lib`目录下所有的jar包中的类和额外的类，也包括**slf4j-api-1.7.7.jar** jar包中的`org/slf4j/LoggerFactory`类。日志显示的类加载器是`WebappClassLoader`。
- 当应用程序在解析`"org.slf4j.impl.StaticLoggerBinder`类的`getLoggerFactory()`方法时，发现同一个类有两个不同的类加载器，类加载器冲突，所以抛出`java.lang.LinkageError`异常。

### LinkageError和ClassCastException的区别
LinkageError和ClassCastException本质原因一样，都是因为相同的类被多个的类加载器加载。ClassCastException一般发生在不同对象类型进行转换时。LinkageError出现的情况就更为复杂一点，一般发生在被多个类加载器加载的类发生交互时出现。当然，不同类对象之间进行类型转换也会出现ClassCastException异常。

## 一点疑惑
虽然问题原因查明白了，但是还是不太明白日志最后一句`“have different Class objects for the type
org/slf4j/ILoggerFactory used in the signature”`的**“used in the signature”**是什么意思，不知道是否是指Java类方法的签名。如果是，Java类方法的签名又和Java类加载有什么关系呢？  
另外，最好自己可以动手造一下`java.lang.LinkageError`异常，这样可以加深对类加载的理解。  

这两个问题还要自己持续跟踪。

## 参考资料
[SLF4J error: class loader have different class objects for the type](http://stackoverflow.com/questions/29504180/slf4j-error-class-loader-have-different-class-objects-for-the-type)  
[Tomcat 8: Class Loader HOW-TO](https://tomcat.apache.org/tomcat-8.0-doc/class-loader-howto.html)  
[深入浅出ClassLoader（译）](https://yq.aliyun.com/articles/2890)  
[java的反射通过类名加载类和ClassLoader通过类名加载类有什么区别？](https://www.zhihu.com/question/38472316/answer/79729218)  
[深入探讨 Java 类加载器](https://www.ibm.com/developerworks/cn/java/j-lo-classloader/)  
[JAR files you should never include in your web-app](http://wiki.metawerx.net/wiki/JARFilesYouShouldNeverIncludeInYourWebapp)  
[Tomcat启动时类加载顺序及运行时类载入顺序](http://shuhucy.iteye.com/blog/1900231)  
[Tomcat 8 类加载机制](http://wiki.jikexueyuan.com/project/tomcat/classloading.html)  
[图解Tomcat类加载机制](http://www.cnblogs.com/xing901022/p/4574961.html)  
[Signature (functions)](https://developer.mozilla.org/en-US/docs/Glossary/Signature/Function)  
[How to deal with LinkageErrors in Java?](http://stackoverflow.com/questions/244482/how-to-deal-with-linkageerrors-in-java)  
[Java Signatures](http://tcljava.sourceforge.net/docs/TclJava/JavaSignatures.html)  
[Error: Servlet Jar not Loaded… Offending class: javax/servlet/Servlet.class](http://stackoverflow.com/questions/1993493/error-servlet-jar-not-loaded-offending-class-javax-servlet-servlet-class)
