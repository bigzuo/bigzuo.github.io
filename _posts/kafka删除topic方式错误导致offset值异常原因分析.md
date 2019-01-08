---
title: kafka删除topic方式错误导致offset值异常原因分析
date: 2017-03-19 11:41:37
categories: Kafka
---

  
  
> 原创文章，如需转载，请注明来自：<https://bigzuo.github.io/>  

## 版本信息
Kafka 0.8.2，JDK1.7

## 问题现象
最近我们在生产环境执行删除无用的kafka topic的操作时，因为错误的按照8.2版本之前的删除方式操作8.2.2版本的kafka，导致删除过程异常，删除后出现consumer正在消费的其他正常topic的partition的offset值偏移的情况，导致大量消息重复消费，并且产生连锁反应，给我们的系统稳定性产生明显影响。  
<!-- more -->
如下日志所示，正常情况下，producer将消息发送到broker后，consumer会迅速消费，并将offset值更新到zookeeper中，所以offset值基本和broker中保存log的数量一致，lag的数量（*lag的值表示的是consumer还未消费、积压在broker中的消息数量*）应该很小，并且最好为零。  

```java
Group           Topic                          Pid Offset          logSize         Lag             Owner
jd-group        prod_INSERT_PRAISE_TOPIC       0   38329811        38329816        5               jd-group_CNSZ044119-1488476503274-fc7c1093-0
jd-group        prod_INSERT_PRAISE_TOPIC       1   38277005        38277009        4               jd-group_CNSZ044120-1488476511246-82fa3f97-0
jd-group        prod_INSERT_PRAISE_TOPIC       2   38260119        38260129        10              jd-group_CNSZ044121-1488476514708-d6c398fd-0
jd-group        prod_INSERT_PRAISE_TOPIC       3   38295398        38295406        8               jd-group_CNSZ044122-1488476519807-56a61327-0
jd-group        prod_INSERT_PRAISE_TOPIC       4   38296566        38296572        6               jd-group_CNSZ044213-1488476524985-b502939d-0
jd-group        prod_INSERT_PRAISE_TOPIC       5   38326045        38326049        4               jd-group_CNSZ044214-1488476532025-dd1639a0-0
jd-group        prod_INSERT_PRAISE_TOPIC       6   38368348        38368356        8               jd-group_CNSZ044215-1488476538362-14ba2724-0
jd-group        prod_INSERT_PRAISE_TOPIC       7   38251390        38251404        14              jd-group_CNSZ044216-1488476539837-e12b2a19-0
```  
</br>
但是由于我们删除无用topic时操作错误，导致正常topic的partition的offset值发生偏移，即offset值变小（*如下日志所示*），引起大量消息重复消费。  

```java
Group           Topic                          Pid Offset          logSize         Lag             Owner
jd-group        prod_INSERT_PRAISE_TOPIC       0   31420318        32928394        1508076         jd-group_CNSZ044119-1484935128585-91da0bb8-0
jd-group        prod_INSERT_PRAISE_TOPIC       1   31385094        32886670        1501576         jd-group_CNSZ044120-1484935745537-76bc983a-0
jd-group        prod_INSERT_PRAISE_TOPIC       2   31341677        32860353        1518676         jd-group_CNSZ044121-1484935410811-1e5fc79e-0
jd-group        prod_INSERT_PRAISE_TOPIC       3   31368494        32885584        1517090         jd-group_CNSZ044122-1484934836225-2bed5d25-0
jd-group        prod_INSERT_PRAISE_TOPIC       4   31372038        32891129        1519091         jd-group_CNSZ044213-1485046918860-311fa6e2-0
jd-group        prod_INSERT_PRAISE_TOPIC       5   31403402        32921221        1517819         jd-group_CNSZ044214-1484935779973-d081f8df-0
jd-group        prod_INSERT_PRAISE_TOPIC       6   31455690        32963013        1507323         jd-group_CNSZ044215-1484935065864-3a0cd250-0
jd-group        prod_INSERT_PRAISE_TOPIC       7   31357985        32860016        1502031         jd-group_CNSZ044216-1484935015571-66703764-0
```  

## 原因分析
在kafka 0.8.2版本之前，kafka删除topic的功能存在bug，即**无法通过kafka-topics  --delete**一条命令就彻底删除topic数据，这个命令只会在zookeeper中注销topic信息，并标记为**“Topic topic_name is marked for deletion.”**，如果需要彻底删除topic数据，需要以下几步操作：  
1、前提要保证kafka启动时，在server.properties配置文件中配置**delete.topic.enable=true**  
2、执行删除命令：**./bin/kafka-topics  --delete --zookeeper 【zookeeper server】  --topic 【topic name】**  
3、进入到kafka的**log.dirs**目录，删除掉对应topic的所有日志文件  
4、登录zookeeper客户端，删除**/brokers/topics**目录下对应的topic节点数据，至此所有删除操作全部完成。  
</br>
但是在0.8.2版本中，删除topic的操作经过优化，只需要两步就可以彻底删除topic所有数据，即配置并生效**delete.topic.enable=true**，然后执行**kafka-topics  --delete**命令即可。这个命令不仅会删除zookeeper中的topic数据，也会删除掉**log.dirs**目录下对应topic的所有日志数据，**并且不影响新建同名的topic**。在0.8.2版本中，对于删除topic的操作，topic工具会将该topic名字存于zookeeper的**/admin/delete_topics**中，如果**delete.topic.enable=true**,则controller注册在**/admin/delete_topics**上的watch会被fire，controller就会通过回调的方式向对应的broker发送**StopReplicaRequest**的请求，然后删除该topic的所有数据。 
 
所以，当我们在生产环境按照0.8.1版本的操作方式去删除0.8.2版本的topic时就会出现异常，因为在执行完**kafka-topics  --delete**命令后，topic的状态已经被改变，同时broker和zookeeper都会执行删除、同步操作，而在此时，我们又手动进入到kafka的**log.dirs**目录，删除掉对应topic的所有日志文件，并且又进入到zookeeper服务器，删除**/brokers/topics**目录下对应的topic节点数据，导致本可以正常进行的删除、同步操作出现异常，进而导致存储在zookeeper中的consumer消费其他正常topic 的offset信息发生丢失，并且我们在consumer端又配置了**auto.offset.reset=smallest**[^offset.reset]，所以当offset信息丢失、没有初始化或者出现异常时，consumer会自动从最小的offset处开始消费，引起已经消费过的数据重复消费。  


## 总结反思
出现这种问题一是因为我们缺少kafka运维经验，之前并没有操作过删除kafka topic的经历；二是测试不充分。我们测试环境和生产环境的kafka版本都是0.8.2，但是在测试环境测试删除操作时，只删除了一个topic，产生的影响较小，所以错误操作的影响并没有表现出来。而我们在生产环境操作时，一次就批量处理了600多个topic，并且生产环境的数据量要比测试环境大很多，所以问题就显而易见的暴露了出来。  
所以在生产环境对不熟悉的组件进行任何操作时，务必要在测试环境充分测试，最好熟悉操作流程和原理，避免这种处理结果和预期不符，并造成生产问题的情况再次出现。  

## 反馈建议
**邮箱：**<bigzuo@163.com>  

## 参考资料
[kafka高可用原理](http://kaimingwan.com/post/kafka/kafkagao-ke-yong-yuan-li#toc_18)  
[Kafka-0.8.2 新特性](http://mdba.cn/2016/12/21/kafka-0-8-2-%E6%96%B0%E7%89%B9%E6%80%A7/) 
[怎么彻底删除kafka的topic，然后重建？](http://openskill.cn/question/108)  
 
[^offset.reset]: auto.offset.reset定义了consumer在zooKeeper中发现没有初始的offset时或者发现offset非法时定义comsumer的行为，常见的配置有smallest:自动把offset设为最小的offset；largest:自动把offset设为最大的offset；anything else:抛出异常。