---
layout:     post
title:      深入理解 Java 类加载器及类加载机制
date:       2017-05-16 21:50:55
header-img: img/backgroud.png
catalog: true
tags:
    - Java/JVM
    - 开源框架
---

## 什么是类加载器
## JVM类加载机制
## Tomcat类加载机制
### Tomcat 类加载顺序和时间
## 线程类加载器
## 类加载器和容器
## 什么是签名（signature）
## 理解类加载器的作用


接口A，类B实现接口A。A和B由不同类加载器加载，会有什么问题？

什么是SPI？SPI类加载机制？动手实现？

## 参考资料
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
[真正理解线程上下文类加载器（多案例分析）](http://blog.csdn.net/yangcheng33/article/details/52631940#reply)
