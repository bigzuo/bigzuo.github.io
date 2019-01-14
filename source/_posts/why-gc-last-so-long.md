---
title: 为什么Java GC 耗时这么长？
date: 2017-07-16 19:31:17
tags: Java/JVM
---

## 是什么导致请求响应很慢？
最近公司的一个系统测试环境上了部分新功能后，发现测试环境服务响应很慢，很多没有任何业务逻辑功能的请求，响应也会超过10s。我们对代码加上性能日志后，发现请求的处理会莫名的“卡住”，而不是因为在等待资源产生的停顿。我们在排除掉代码的原因后，开始分析JVM参数和服务器配置是否合理。  

首先我们检查了JVM参数配置，具体参数如下：  

```
-Xms768M -Xmx768M -XX:MaxNewSize=256m -XX:MaxPermSize=128m -XX:+UseConcMarkSweepGC -XX:+PrintGCTimeStamps -verbose:gc -Xloggc:logs/gc.log
```
我们服务器配置是1核2G，实际可用空间是1.7G，因此结合我们的JVM配置，发现当前配置并无不合理之处。并且测试环境应用请求量并不大，所以也解释不了请求卡顿的原因。  
<!-- more -->  
然后，我们就查看了一下GC日志。结果从GC日志中发现应用在新生代垃圾回收时，耗时很长，因此造成服务长时间停顿。从下面的日志可以看出，**一次新生代垃圾回收，居然耗时84.6s**。  

```
Heap after GC invocations=139 (full 4):
 par new generation   total 235968K, used 18749K [0x00000000d0000000, 0x00000000e0000000, 0x00000000e0000000)
  eden space 209792K,   0% used [0x00000000d0000000, 0x00000000d0000000, 0x00000000dcce0000)
  from space 26176K,  71% used [0x00000000de670000, 0x00000000df8bf780, 0x00000000e0000000)
  to   space 26176K,   0% used [0x00000000dcce0000, 0x00000000dcce0000, 0x00000000de670000)
 concurrent mark-sweep generation total 524288K, used 263584K [0x00000000e0000000, 0x0000000100000000, 0x0000000100000000)
 Metaspace       used 104919K, capacity 108067K, committed 109632K, reserved 1144832K
  class space    used 12627K, capacity 13455K, committed 13756K, reserved 1048576K
}
{Heap before GC invocations=139 (full 4):
 par new generation   total 235968K, used 228541K [0x00000000d0000000, 0x00000000e0000000, 0x00000000e0000000)
  eden space 209792K, 100% used [0x00000000d0000000, 0x00000000dcce0000, 0x00000000dcce0000)
  from space 26176K,  71% used [0x00000000de670000, 0x00000000df8bf780, 0x00000000e0000000)
  to   space 26176K,   0% used [0x00000000dcce0000, 0x00000000dcce0000, 0x00000000de670000)
 concurrent mark-sweep generation total 524288K, used 263584K [0x00000000e0000000, 0x0000000100000000, 0x0000000100000000)
 Metaspace       used 104920K, capacity 108067K, committed 109632K, reserved 1144832K
  class space    used 12627K, capacity 13455K, committed 13756K, reserved 1048576K
2017-06-02T03:31:33.092+0800: 29548.385: [GC (Allocation Failure) 2017-06-02T03:31:34.196+0800: 29548.586: [ParNew: 228541K->26176K(2968K), 84.6730786 secs] 492125K->291501K(760256K), 87.0223483 secs] [Times: user=0.07 sys=0.02, real=87.00 secs]
```
> 正常情况下，应用一般都会配置输出GC日志，但是输出信息都比较简单，不会像上面输出这么详细，不足以满足问题排查的需要。所以我们增加了“-XX:+PrintHeapAtGC -XX:+PrintGCDetails -XX:+PrintGCTimeStamps -XX:+PrintGCDateStamps” 等相关参数，增加GC日志的输出信息。  


通过上面的日志可以确定是应用的GC活动造成的请求响应停顿。那么问题来了，为什么应用的垃圾回收会耗时这么长呢？  

## GC耗时为何这么长？
通过执行`jstat -gcutil <PID> 500`命令查看各分区的使用率变化，可以看到当新生代的Eden区使用率达到100%时，触发了一次新生代的垃圾回收，并且恰好此时Survivor0和Survivor1区空间使用率也都达到100%。随着新生代垃圾回收的进行，老年代（Old区）的空间使用率也在逐渐增加，但是增加非常缓慢。然后随后因为新生代没有足够空间分配新增对象，系统还触发了一次**Full GC**。

```
S0     S1     E       O      M     CCS     YGC    YGCT    FGC    FGCT     GCT
0.00 100.00  99.81  64.21  95.79  92.61    457  456.966    12   96.089  553.056
0.00 100.00  99.85  64.21  95.79  92.61    457  456.966    12   96.089  553.056
99.97 100.00 100.00  64.21  95.79  92.61    458  456.966    12   96.089  553.056
99.97 100.00 100.00  64.24  95.79  92.61    458  456.966    12   96.089  553.056
99.97 100.00 100.00  64.25  95.79  92.61    458  456.966    12   96.089  553.056
100.00 100.00 100.00  64.51  95.79  92.61    458  456.966    12   96.089  553.056
100.00 100.00 100.00  64.51  95.79  92.61    458  456.966    12   96.089  553.056
100.00 100.00 100.00  64.53  95.79  92.61    458  456.966    12   96.089  553.056
100.00 100.00 100.00  64.57  95.79  92.61    458  456.966    12   96.089  553.056
100.00 100.00 100.00  64.64  95.79  92.61    458  456.966    12   96.089  553.056
100.00 100.00 100.00  64.67  95.79  92.61    458  460.994    13   96.089  557.084
100.00 100.00 100.00  64.67  95.79  92.61    458  460.994    13   96.089  557.084
100.00 100.00 100.00  64.67  95.79  92.61    458  460.994    13   96.089  557.084
100.00 100.00 100.00  64.67  95.79  92.61    458  460.994    13   96.089  557.084
```

**新生代回收期间，老年代仍有剩余空间保存新生代存活对象，但是新生代的垃圾回收和老年代的空间增长都非常缓慢，基本可以确定时间是消耗在对象转移操作上**。那么，此时的服务器在做什么呢？通过在终端执行`vmstat 1`命令可以看到Linux系统的状态信息。从下面的输出中，我们可以看到虚拟内存使用已经超过1G（swpd参数），IO异常频繁（bi和bo参数），并且CPU大部分时间都在等待IO（wa参数）。  

```
 procs -----------memory---------- ---swap-- -----io---- --system-- -----cpu-----
 r  b   swpd   free    buff  cache   si   so    bi    bo   in   cs us sy id wa st
 1  3 1157776 516932   1248  26892 17586 1970 17722  1970 1177 2675  2  7  0 90  1
 1  1 1191676 540880   1388  27124 10156 17170 10708 17196  985 1637  1 15  0 83  2
 0  5 1207336 559628   1404  27204  526 7892   564  7940  381  512  1  6  0 93  1
 0  7 1229464 567872   1532  27308  132 11086   226 11104  341  455  1  7  0 92  0
 0  7 1245776 601848   1568  19688  574 8162  1374  8186  456  668  1  7  0 92  1
 0  8 1245752 605248   1540  15588  100    0   184    62  288  464  1  1  0 99  0
 0  7 1245184 603424   3568  15856 2098    0  5684    40  522  725  2  4  0 93  1
 0  3 1254304 590780   4808  22780 10174 5564 15214  5586 1085 1393  7 18  0 74  1
```
看到这里的时候，就可以断定是**系统大量使用了虚拟内存，并且垃圾回收期间，无效对象的回收和存活对象空间的转移引发了大量的swap空间交换，具体就是JVM进行GC时，需要对堆已使用的空间进行遍历。如果有一部分内容被交换到swap中，遍历到这部分内容时就要将其换回内存，同时如果内存空间不足，这时还需要将内存中堆的一部分换到swap中，于是遍历堆空间的过程中，极端情况会把整个堆空间轮流往swap中读写一遍。而系统读取swap空间的数据速度又非常慢，并且公司对云主机又进一步限制了IO读写的速度，所以导致垃圾回收过程非常缓慢，进而引起系统响应卡顿**。  

但是系统为什么会用这么多虚拟内存空间呢？在之前的理解中，一般是在物理内存不够的情况下，系统才会使用虚拟内存。我们通过`top`命令输出服务器内存及swap使用率：**发现在还有剩余内存的时候，系统就已经开始用swap了**。这是为什么呢？Linux有一个内核参数`vm.swappiness`，控制当内存空间剩余还有多少的时候，就开始使用swap。该参数值范围从0到100，当该参数=0，表示尽可能使用物理内存，避免swap空间交换;该参数=100，表示尽可能使用swap空间，这也告诉内核疯狂的将数据移出物理内存移到swap缓存中。我们通过`cat /proc/sys/vm/swappiness`命令查看Linux系统这个参数设置的值是多少，我们测试环境服务器设置的值是Linux系统的默认值60。  

当我们把这个参数设置成0，并把物理内存增加到4G后，再执行`vmstat 1`命令，可以看到系统IO操作已经减少很多，并且CPU时间用于等待IO的比例几乎降为0，说明系统基本不再发生swap空间交换操作。  

```
[root@SHB-L0042573 bin]# vmstat 1
procs -----------memory---------- ---swap-- -----io---- --system-- -----cpu-----
 r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st
 0  0      0 964484   8088 1224640    0    0   773   862  171   63  2  2 77 19  0
 0  0      0 964500   8088 1224656    0    0     0     0  550  623  1  1 98  0  0
 0  0      0 964236   8096 1224648    0    0     0    48  717  828  1  3 68 27  1
 0  0      0 964252   8096 1224664    0    0     0    24  552  642  1  0 99  0  0
 0  0      0 964484   8096 1224664    0    0     0     0  681  783  1  2 97  0  1
 0  0      0 964532   8104 1224660    0    0     0    92  568  686  1  1 96  2  1
 0  0      0 961380   8104 1224664    0    0     0     4  667  772  3  4 93  0  1
```
我们再看一下现在垃圾回收的时间，已经降到正常水平，基本都在0.1s以内完成。再统计请求响应时间，结果也一切正常。  

```
Heap after GC invocations=306 (full 61):
 par new generation   total 235968K, used 26176K [0x00000000d0000000, 0x00000000e0000000, 0x00000000e0000000)
  eden space 209792K,   0% used [0x00000000d0000000, 0x00000000d0000000, 0x00000000dcce0000)
  from space 26176K, 100% used [0x00000000de670000, 0x00000000e0000000, 0x00000000e0000000)
  to   space 26176K,   0% used [0x00000000dcce0000, 0x00000000dcce0000, 0x00000000de670000)
 concurrent mark-sweep generation total 524288K, used 302719K [0x00000000e0000000, 0x0000000100000000, 0x0000000100000000)
 Metaspace       used 122506K, capacity 126633K, committed 127156K, reserved 1161216K
  class space    used 14502K, capacity 15433K, committed 15580K, reserved 1048576K
}
{Heap before GC invocations=306 (full 61):
 par new generation   total 235968K, used 235968K [0x00000000d0000000, 0x00000000e0000000, 0x00000000e0000000)
  eden space 209792K, 100% used [0x00000000d0000000, 0x00000000dcce0000, 0x00000000dcce0000)
  from space 26176K, 100% used [0x00000000de670000, 0x00000000e0000000, 0x00000000e0000000)
  to   space 26176K,   0% used [0x00000000dcce0000, 0x00000000dcce0000, 0x00000000de670000)
 concurrent mark-sweep generation total 524288K, used 302719K [0x00000000e0000000, 0x0000000100000000, 0x0000000100000000)
 Metaspace       used 122511K, capacity 126639K, committed 127156K, reserved 1161216K
  class space    used 14502K, capacity 15434K, committed 15580K, reserved 1048576K
2017-06-03T10:51:41.998+0800: 62566.282: [GC (Allocation Failure) 2017-06-03T10:51:41.998+0800: 62566.283: [ParNew: 235968K->26176K(235968K), 0.0586496 secs] 538687K->344177K(760256K), 0.0591796 secs] [Times: user=0.08 sys=0.00, real=0.06 secs]
```
## Java进程为什么会占用这么多虚拟内存？

现在问题解决了，但是在解决问题的过程中，产生了一个疑问：**Java进程为什么会占用这么多虚拟内存**？我们看一下在排查问题的过程中`top`命令的输出信息：

```
top - 16:46:07 up 44 days,  6:28,  1 user,  load average: 0.06, 0.45, 0.73
Tasks: 155 total,   1 running, 148 sleeping,   6 stopped,   0 zombie
Cpu(s):  1.2%us,  0.8%sy,  0.0%ni, 92.3%id,  5.0%wa,  0.0%hi,  0.2%si,  0.5%st
Mem:   3792408k total,  2847332k used,   945076k free,    28264k buffers
Swap:  6291452k total,   184688k used,  6106764k free,   797164k cached

   PID USER      PR  NI  VIRT  RES  SHR S %CPU %MEM    TIME+  COMMAND
 71995 wls81     20   0 4561m 1.7g 7244 S  3.7 46.1 144:16.27 java
  2806 root      20   0  107m 1692  744 S  0.3  0.0  44:40.43 OSWatcher.sh
```
我们可以看到应用系统Java进程用了4.5G虚拟内存（VIRT参数），但是我们配置的Java进程可以使用的最大堆空间是1.5G。那么Java进程使用的空间为什么比我们设置的最大堆空间大这么多呢？  

首先我们要明白，一个Linux进程所占用的虚拟内存包括了这个进程所需要的所有空间，比如堆空间、程序代码、依赖的第三方库、数据等所占用的空间，这些空间并不一定是进程实际运行所占用的空间。就像公司里面给每个人分配了一个2平方米大小的工位，但是我们每个人才实际占用约0.5平方米。其中2平方米就类似于进程占用的虚拟内存，0.5平方米就是进程运行时占用的实际空间。明白这个后，我们再继续分析Java进程的空间占用。  
Java进程和运行在Linux系统上的其他进程一样，内存占用都由几部分组成：**内核内存 + 代码区 + 数据区 + 堆区 + 栈区 + 未使用区域**，区别在于JVM为了减少系统调用次数及内存空间和用户空间之间的数据拷贝（如Java NIO），将很多本属于操作系统管理的区域也移植到了JVM内部，并且JVM对堆空间也做了更加细致的分区。  

![](https://bigzuo.coding.me/bigzuo/images/java_meminfo.png)
> 该图引用自[Linux与JVM的内存关系分析](https://tech.meituan.com/linux-jvm-memory.html)

因此，**应用程序占用的内存空间 ≈ [-Xmx] + [-XX:MaxPermSize] + number_of_threads * [-Xss] + Java NIO**。但是JVM本身运行也需要空间，比如GC操作、JIT优化、JNI代码等所占用的空间，所以Java进程所占用的内存空间，一定会比我们设置的最大堆空间要大。  

除此之外，还有一点非常重要但是很少有人提及的是Java线程最终映射的还是Linux操作系统的线程，Linux操作系统为了解决多线程内存分配竞争的问题，在创建线程时，会为每一个线程分配一定的虚拟内存空间（也可称之为缓冲区），使得线程之间相互独立，互不干扰。然而这部分虚拟内存只有在实际使用时，才会分配物理内存。但是这部分“空间”也被统计在了虚拟内存中。在64位的Linux操作系统上，为每个线程分配的虚拟内存是64M。相信大部分Java程序都是以多线程的方式运行，因此虚拟内存的占用一般都比较高，不过一般情况下都不用在意。  

## 小结

这次排查问题还是用了挺长时间，过程中也走了一些弯路，比如在很多次调整JVM参数都没有效果的时候，我尝试改过这个参数：  

```
-XX:CMSInitiatingOccupancyFraction=80  //老年代使用达到多少时，就触发老年代的回收
```
后来才明白，JVM默认的92%是合理的，因为老年代增长本身比较慢，改成80%后，反而会造成部分空间浪费。  
其次就是思路和经验很重要，以后还要更多的积累。

## 参考资料
[Why does my Java process consume more memory than Xmx?](https://plumbr.eu/blog/memory-leaks/why-does-my-java-process-consume-more-memory-than-xmx)  
[Virtual Memory Usage from Java under Linux, too much memory used](https://stackoverflow.com/questions/561245/virtual-memory-usage-from-java-under-linux-too-much-memory-used)  
[Linux与JVM的内存关系分析](http://tech.meituan.com/linux-jvm-memory.html)  
[Java 进程占用 VIRT 虚拟内存超高的问题研究](http://www.cnblogs.com/seasonsluo/p/java_virt.html)  
[死磕内存篇 --- JAVA进程和linux内存间的大小关系](http://www.cnblogs.com/springsource/p/6097736.html)  
[为什么linux下多线程程序如此消耗虚拟内存](http://blog.csdn.net/chen19870707/article/details/43202679)  