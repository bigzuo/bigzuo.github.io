---
title: kafka异常关闭（kill -9）后导致无法重启
date: 2017-03-19 11:48:25
categories: Kafka
---
> 原创文章，如需转载，请注明来自：<https://bigzuo.github.io/>

## 版本信息
Kafka 0.8.2，JDK1.7

## 问题现象
项目组生产环境kafka集群在进行CPU、内存升级时，升级后重启kafka broker时出现日志文件加载异常的报错，导致重启失败。具体报错日志如下：  

```java
2017-02-10 00:35:52,237 ERROR log.LogManager: There was an error in one of the threads during logs loading: java.lang.StringIndexOutOfBoundsException: String index out of range: -1
2017-02-10 00:35:52,237 INFO log.Log: Recovering unflushed segment 0 in log Bacth_time_DB_B_ALS_201702080800009457085-9.
2017-02-10 00:35:52,238 INFO log.Log: Completed load of log Bacth_time_DB_B_ALS_201702080800009457085-9 with log end offset 408
2017-02-10 00:35:52,239 FATAL server.KafkaServer: [Kafka Server 1], Fatal error during KafkaServer startup. Prepare to shutdown
java.lang.StringIndexOutOfBoundsException: String index out of range: -1
        at java.lang.String.substring(String.java:1911)
        at kafka.log.Log$.parseTopicPartitionName(Log.scala:833)
        at kafka.log.LogManager$$anonfun$loadLogs$2$$anonfun$3$$anonfun$apply$7$$anonfun$apply$1.apply$mcV$sp(LogManager.scala:138)
        at kafka.utils.Utils$$anon$1.run(Utils.scala:54)
       at java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:471)
        at java.util.concurrent.FutureTask.run(FutureTask.java:262)
        at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1145)
        at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:615)
        at java.lang.Thread.run(Thread.java:745)
```  
<!-- more -->
## 问题原因
在系统升级前，我们需要停掉kafka broker，但是我们在停kafka 进程时，没有采用**cleanShutDown**的方式，而是直接使用了粗暴的**“kill -9”**结束了kafka进程，导致一些已经提交的日志在追加到硬盘时出现异常，即出现数据错误。然后当我们重启时，当加载到这些异常文件时，就出现异常，导致重启失败。

## 原理分析
在kafka文件存储中，同一个topic一般都有多个partition，每个partition为一个目录，目录的命名规则为topic名称+partition序列号。而消息数据在每个partition内部都被顺序分配到几个大小相等的segment数据文件中。segment又由两部分组成，分别是文件后缀是“.index”的index file和后缀是“.log”的data file，这两个文件一一对应，分别表示索引文件和日志文件。segment存放文件的大小由broker 中**server.properties**配置文件中**log.segment.bytes**配置项决定的。  
当broker收到producer发送过来的数据时，会将数据追加到所在partition中最新的segment文件中，如果该segment文件大小超过**log.segment.bytes**设置的值，服务器就会重新生成一个segment文件（即一对index文件和data文件），文件的名字就是上一个segment文件中最后一条消息的offset值，然后将数据追加（append）到新的segment文件中。在正常情况下，index文件和data文件是一一对应的，并且内容也是完全一一对应的。但是
操作在非**cleanShutDown（kill -9）**情况下， 一个**log sement**的**log**及**index**文件末尾可能写入一些不合法的数据(invalid)，导致broker重启时，加载index文件异常，进而导致进程关掉，无法重启的问题。

## 解决方案
由于kafka是集群工作模式，在合理设置replicate的情况下，其他broker也会同步日志信息，所以我们直接清除掉异常broker对应**log.dirs**目录下的所有日志文件，再进行重启就能正常启动，并且这台机器也会从对应topic的其他ISR机器上同步历史日志文件。不过，**在极端情况下，部分leader是在这台异常broker上的topic partition可能会丢失一些未同步的数据。**

## 反馈建议
**邮箱：**<bigzuo@163.com> 

## 参考资料
[Existing directories under the Kafka data directory without any data cause process to not start](https://issues.apache.org/jira/browse/KAFKA-742)  
[System Test Hard Failure cases : "Fatal error during KafkaServerStable startup" when hard-failed broker is re-started](https://issues.apache.org/jira/browse/KAFKA-757)  
[Implement clean shutdown in 0.8](https://issues.apache.org/jira/browse/KAFKA-340)  
[Kafka重启出错：Corrupt index found](http://blog.csdn.net/jsky_studio/article/details/42012561)  
[Kafka文件存储机制那些事](http://tech.meituan.com/kafka-fs-design-theory.html) 
