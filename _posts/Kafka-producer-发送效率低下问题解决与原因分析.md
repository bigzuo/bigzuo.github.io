---
title: Kafka producer 发送效率低下问题解决与原因分析
date: 2017-03-13 21:39:08
categories: Kafka
---
  
> 原创文章，如需转载，请注明来自：<https://bigzuo.github.io/>
  
## 版本信息
Kafka 0.8.2 ，JDK1.7，producer api: kafka.javaapi.producer.Producer


## 问题现象
   项目组在16年引入kafka组件作为消息中间件，之后慢慢有更多功能接入kafka，虽然中间出过几次小问题，但均不影响主要功能。直到近期一个新功能也接入kafka后，我们发现kafka集群CPU使用率变得很高，正常运行时CPU使用率都在40%-60%，并且随着新功能往kafka发送的消息量越来越大，出现发送延迟也越来越明显，后来基本大部分情况下往kafka发送一条消息需要10s以上，以至于导致该新功能完全不可用。但是这个时候我们发现kafka集群负载正常、IO正常，并且部分接入同一个kafka集群的其他功能发送消息也都正常。
  
<!-- more -->
## 问题解决

在尝试多种解决方案无果后，我们开始重点分析为什么访问的是同一个kafka集群，但是部分系统往kafka发送消息就完成正常，我们往kafka发送消息效率就很差。后来一对比发现，两个客户端采用producer API不一样，我们用的是旧kafka API，即是用scala语言编写的kafka.javaapi，他们使用的是新kafka API，即用Java 语言编写的```org.apache.kafka.clients```。我们分析两种API的发送机制后发现：旧API有两种发送方式，同步和异步，即**sync**和**async**两种发送方式，我们代码实际采用的是默认发送方式：同步方式，新API只支持异步发送方式，于是我们尝试将我们的发送方式也改成异步发送，然后发现问题消失，客户端往kafka 发送消息时耗时基本都在0ms。  

## 原因分析
旧版本API 发送流程可以概括如下图，这幅图包括3个部分，中间的（深蓝色矩形）部分的流程是发送的核心流程（同步发送）；左边（淡蓝色）是异步发送时相关的额外流程，右边（黄色）是客户端更新元信息相关的流程。
![image](http://img.blog.csdn.net/20140808173819422?watermark/2/text/aHR0cDovL2Jsb2cuY3Nkbi5uZXQvbGl6aGl0YW8=/font/5a6L5L2T/fontsize/400/fill/I0JBQkFCMA==/dissolve/70/gravity/SouthEast)
</br>
**结合kafka producer源码，梳理出生产者完成一次完整的消息发送，对应的方法调用可以整理为如下图：**
</br>
![image](http://kaimingwan.com/kafka/_image/kafka%E7%94%9F%E4%BA%A7%E8%80%85%E5%8E%9F%E7%90%86%E8%AF%A6%E8%A7%A3/11-23-09.jpg)
</br>
  
  
所以，结合这两幅流程图，我们可以很明显的梳理出同步发送的逻辑：  
1、Producer实例调用其send方法  
2、本质是调用了Handler的handle(message)  
3、handler序列化消息  
4、handler调用dispatchSerializedData方法来调度序列化后的消息  
5、dispatchSerializedData方法调用partitionAndCollate方法对topic的message进行分组（根据获取的leaderBrokerId元数据来对消息分组）  
6、从生产者池中获取不同broker对应的生产者，来真正的发送消息  

以上6个步骤简化来看，可以分为3个主要步骤：**序列化消息数据 -> 获取broker topic原数据对消息数据进行分区 -> 发送**。
  
当采用异步发送方式时，其实就是在同步发送的基础上增加了一些额外流程，来达到异步批量发送的目的。增加的额外流程为：  
1、创建生产者时，会同时创建一个SendThread线程和一个阻塞队列，调用发放方法时，先将消息写入一个阻塞队列  
2、SendThread线程定时轮询阻塞队列，拉取缓存消息  
3、调用同步发送方法将阻塞队列的消息作为一个batch批量发送  

所以**在采用同步发送时，网络消耗和I/O操作都比异步发送多很多，一旦写入数据超过send方法处理能力，就会产生发送阻塞，引起过长的时间延迟。所以，在写入数据频率很高时，应该使用异步发送方式，将数据以batch形式发送，可以同时减少发送者和kafka集群的压力，大幅提高发送效率**。

## 新API特性
在解决问题的同时，我们也对比了新旧API的不同。相对于旧版API而言，新版API提供了更多的特性和更好的优化。下面是我自己梳理了几项新版API相对于旧版API的几个重要不同点：  
1、新版API和旧版API一个最明显的不同是**新版API最终的发送使用的NIO模式，即使用Selector NIO异步非阻塞模式管理连接，读写请求。而旧版API发送请求send,接收响应receive等使用的是一个非NIO模式的阻塞类型的Channel，因此效率要慢很多。**  
2、新版API只支持异步发送，实现原理为异步非阻塞方式，同时send方法提供了回调机制(callback)供用户判断消息是否成功发送。尤其需要注意的是，回调操作是在发送线程中执行的，如果回调操作执行效率较低，则会直接影响发送效率。  
3、新版API总共创建两个线程：执行KafkaPrducer#send逻辑的线程，即“用户主线程“，该线程接收到用户发送的消息后直接缓存在本地，并返回用户执行结果；执行发送逻辑的IO线程，即“Sender线程”，该线程进行实际发送。  
4、其他发送优化，如新版API发送批量数据到broker时，如果对应连接还没建立，则发送线程立即处理下一个batch，而在旧版本API中，同样在采用异步模式、批量发送的情况下，如果发送某批数据到对应broker时发现连接未建立，则会等待建立，发送成功后，再去处理下一批数据。  

总之，无论是kafka官网，还是相关技术网站，都推荐使用新版API，官网对新版API的介绍是“As of the 0.8.2 release we encourage all new development to use the new Java producer. This client is production tested and generally both faster and more fully featured than the previous Scala client.”。
  
## 特别注意
无论是新版API还是旧版本API的异步发送模式，其实执行真正发送操作的只有一个线程，并不存在发送线程池，所以，在一台机器上，如果只有一个producer实例，则一旦写入数据继续增大，超过本地缓存设置的最大容量，就会造成阻塞或者抛出异常，并且所有使用该producer实例的线程都会受影响。所以实际使用时，如果业务数据量过大，建议自己维护一个线程池，创建多个producer实例，实现发送的最大效率，并且某个producer异常时，不影响其他producer实例工作。但同时也注意本次内存分配的开销，避免内存分配过大，影响系统其他性能。

## 反馈与建议
**邮箱：**<bigzuo@163.com>  
  
## 参考资料
[kafka生产者原理详解](http://kaimingwan.com/post/kafka/kafkasheng-chan-zhe-yuan-li-xiang-jie)  
[Kafka源码分析 Producer Scala客户端](http://zqhxuyuan.github.io/2016/01/07/2016-01-07-Kafka-Producer-scala/)  
[Kafka源码分析 Producer客户端](http://zqhxuyuan.github.io/2016/01/06/2016-01-06-Kafka_Producer/)

