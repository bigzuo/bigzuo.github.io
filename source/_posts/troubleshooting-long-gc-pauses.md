---
title: 【翻译】JMV GC 停顿时间过长问题排查
date: 2017-05-31 20:18:26
categories: Java/JVM
---

> 原文地址：[Troubleshooting Long GC Pauses](https://blogs.oracle.com/poonam/troubleshooting-long-gc-pauses)  
> 个人兴趣翻译，能力有限，如有疏漏，请帮忙指正[bigzuo.github.io](https://bigzuo.github.io/)。  

较短的时间停顿是很多企业应用程序运行时最主要的目标，尤其对于一些过长的响应延迟可能会导致事务超时的事务系统。对于运行在JVM上面的一些系统，垃圾回收（GC）有时会造成较长时间的停顿。  
在本文中，我会介绍几种我们可能会遇到的GC导致长时间停顿的场景，并分析我们应该如何诊断和排查这些问题。 
<!-- more --> 

## 原因
下面是应用程序运行时可能会导致GC长时间停顿的几种场景。

## 1、JVM 堆（heap）中有碎片
Java 堆中的碎片可能会引起频繁的GC活动，并且有可能引起GC长时间停顿。这种情况在CMS垃圾收集器并发收集时老年代空间没有启用碎片压缩时出现的可能性更大一点。  
CMS垃圾收集器工作时，由于老年代中存在碎片，年轻代收集时会遇到**“promotion failures”**问题，进而引起**“Concurrent Mode Failure”**失败，最终会触发比并发收集耗时更长的**Full GC**的**stop-the-world**行为。  
这些碎片可能会导致直接在老年代分配对象失败，因而引起**Full GC**，尽管老年代还有很多剩余空间。频繁的碎片导致对象分配失败会造成频繁的**Full GC**，从而从总体上增加了应用的停顿时间。  
下面这段CMS收集器的日志显示出在老年代有大量的碎片，导致新生代回收时出现**“promotion failures”**异常，并且引起CMS回收时**“Concurrent Mode Failure”**。进而引起一次耗时达到17.1365396s的**Full GC**。  

```
{Heap before GC invocations=7430 (full 24):
par new
generation total 134400K, used 121348K
[0x53000000, 0x5c600000, 0x5c600000)
eden space
115200K, 99% used [0x53000000,
0x5a07e738, 0x5a080000)
from space
19200K, 32% used [0x5a080000,
0x5a682cc0, 0x5b340000)
to space 19200K, 0% used [0x5b340000, 0x5b340000, 0x5c600000)
concurrent
mark-sweep generation total 2099200K, used 1694466K [0x5c600000, 0xdc800000, 0xdc800000)
concurrent-mark-sweep perm gen total 409600K,
used 186942K [0xdc800000, 0xf5800000, 0xfbc00000)
10628.167: [GC Before GC:
Statistics for BinaryTreeDictionary:
------------------------------------
Total Free Space: 103224160
Max Chunk Size:
5486
Number of Blocks: 57345
Av. Block Size: 1800
Tree Height:
36
Statistics for IndexedFreeLists:
--------------------------------
Total Free Space: 371324
Max Chunk Size:
254
Number of Blocks: 8591 <---- High fragmentation
Av. Block Size: 43
free=103595484
frag=1.0000 <---- High fragmentation
Before GC:
Statistics for BinaryTreeDictionary:
------------------------------------
Total Free Space: 0
Max Chunk Size: 0
Number of Blocks: 0
Tree Height: 0
Statistics for IndexedFreeLists:
--------------------------------
Total Free Space: 0
Max Chunk Size: 0
Number of Blocks: 0
free=0 frag=0.0000
10628.168: [ParNew (promotion failed)
Desired survivor size 9830400 bytes, new threshold 1 (max
1)
- age 1: 4770440 bytes, 4770440 total
: 121348K->122157K(134400K), 0.4263254 secs]10628,594:
[CMS10630.887: [CMS-concurrent-mark: 7.286/8.682 secs] [Times: user=14.81
sys=0.34, real=8.68 secs] (concurrent mode failure):
1698044K->625427K(2099200K), 17.1365396 secs]
1815815K->625427K(2233600K), [CMS Perm : 186942K->180711K(409600K)]After
GC:
Statistics for BinaryTreeDictionary:
------------------------------------
Total Free Space: 377269492
Max Chunk Size:
377269492
Number of Blocks: 1
Av. Block Size: 377269492
Tree Height: 1
Statistics for IndexedFreeLists:
--------------------------------
Total Free Space: 0
Max Chunk Size: 0
Number of Blocks: 0
free=377269492
frag=0.0000
After GC:
Statistics for BinaryTreeDictionary:
------------------------------------
Total Free Space: 0
Max Chunk Size: 0
Number of Blocks: 0
Tree Height: 0
Statistics for IndexedFreeLists:
--------------------------------
Total Free Space: 0
Max Chunk Size: 0
Number of Blocks: 0
free=0 frag=0.0000
, 17.5645589 secs] [Times: user=17.82 sys=0.06,
real=17.57 secs]
Heap after GC invocations=7431 (full 25):
par new
generation total 134400K, used 0K
[0x53000000, 0x5c600000, 0x5c600000)
eden space
115200K, 0% used [0x53000000,
0x53000000, 0x5a080000)
from space
19200K, 0% used [0x5b340000,
0x5b340000, 0x5c600000)
to space 19200K, 0% used [0x5a080000, 0x5a080000, 0x5b340000)
concurrent
mark-sweep generation total 2099200K, used 625427K [0x5c600000, 0xdc800000,
0xdc800000)
concurrent-mark-sweep perm gen total 409600K,
used 180711K [0xdc800000, 0xf5800000, 0xfbc00000)
}
```

应用程序停顿的时间总共有17.5730653s。

## 2、GC期间系统在做其他操作
有些时候垃圾回收期间发生的一些系统操作也会引起GC停顿时间变长，比如swap空间交换或者网络活动。这些都有可能导致几秒到几分钟的停顿。  
如果你的系统配置了使用swap空间，那么操作系统就会把JVM进程一些不活跃的空间转移到虚拟内存，以便可以释放内存空间给当前进程的活跃线程或系统的其他进程。由于需要操作速度比物理内存慢很多的硬盘，因此swap空间交换是代价非常昂贵的操作。所以，如果在GC期间系统需要swap空间交换，那么GC就会持续更长的时间。  
下面是一段新生代GC持续了29.47s的日志：  

```
{Heap before GC invocations=132 (full 0):
par new
generation total 2696384K, used
2696384K [0xfffffffc20010000, 0xfffffffce0010000, 0xfffffffce0010000)
eden space
2247040K, 100% used [0xfffffffc20010000, 0xfffffffca9270000,
0xfffffffca9270000)
from space
449344K, 100% used [0xfffffffca9270000, 0xfffffffcc4940000, 0xfffffffcc4940000)
to space 449344K, 0% used [0xfffffffcc4940000,
0xfffffffcc4940000, 0xfffffffce0010000)
concurrent
mark-sweep generation total 9437184K, used 1860619K [0xfffffffce0010000,
0xffffffff20010000, 0xffffffff20010000)
concurrent-mark-sweep perm gen total 1310720K,
used 511451K [0xffffffff20010000, 0xffffffff70010000, 0xffffffff70010000)
2013-07-17T03:58:06.601-0700: 51522.120: [GC Before GC: :
2696384K->449344K(2696384K), 29.4779282 secs]
4557003K->2326821K(12133568K) ,29.4795222 secs] [Times: user=915.56
sys=6.35, real=29.48 secs]
```

相应的系统**‘vmstat’**命令在03:58输出的日志如下：  

```
kthr memory page disk faults cpu
r b w swap free re mf pi
po fr de sr s0 s1 s2 s3 in sy cs us sy id
20130717_035806 0 0 0 77611960 94847600 55 266 0 0 0 0 0
0 0 0 0 3041 2644 2431 44 8 48
20130717_035815 0 0 0 76968296 94828816 79 324 0 18 18 0
0 0 0 1 0 3009 3642 2519 59 13 28
20130717_035831 1 0 0 77316456 94816000 389 2848 0 7 7 0
0 0 0 2 0 40062 78231 61451 42 6 53
20130717_035841 2 0 0 77577552 94798520 115 591 0 13 13 0
0 13 12 1 0 4991 8104 5413 2 0 98
```

这次**Minor GC**持续了约29s。相对应的在此期间系统**‘vmstat’**命令输出信息显示系统可用的swap空间减少了差不多600M。这意味着在GC期间一些当前进程非必须的内存页从物理内存中被移到了swap空间。  
从上面的信息可以看出，系统的物理内存不够所有运行在系统上面的进程使用。解决的方式就是运行尽量少的进程，同时增加更多的物理内存。上面的日志展示永久代配置的最大使用空间是9G，但是仅有1.8G使用了物理内存。因此有效的解决方式是减少堆空间的大小，减少物理内存的压力，尽量避免或者减少swap空间的交换活动。  
除了swap空间交换，我们也要监控在GC期间是否有IO操作或者网络活动。这两项可以使用**‘iostat’**和**‘netstat’**工具监控。同样使用**‘mpstat’**工具输出CPU统计信息观察GC停顿期间是否有可用的CPU资源也非常有用。

## 3、过小的堆空间配置
如果应用程序占用的空间超过我们为JVM设置的最大堆空间，那么就会导致频繁的GC操作。因为堆空间过小，为对象分配空间的请求失败，JVM就会调用GC操作，释放空间。但是由于每次GC并不能够释放足够的空间，因此越来越多的对象分配失败的请求会引起更多的GC调用。  
对应用程序而言，**Full GC**会引起更长的应用停顿。如下面的日志所示，因为永久代空间基本已经满了，所以在永久代分配对象的操作失败，触发了一次**Full GC**：  

```
166687.013: [Full GC [PSYoungGen:
126501K->0K(922048K)] [PSOldGen: 2063794K->1598637K(2097152K)]
2190295K->1598637K(3019200K) [PSPermGen: 165840K->164249K(166016K)],
6.8204928 secs] [Times: user=6.80 sys=0.02, real=6.81 secs]
166699.015: [Full GC [PSYoungGen:
125518K->0K(922048K)] [PSOldGen: 1763798K->1583621K(2097152K)]
1889316K->1583621K(3019200K) [PSPermGen: 165868K->164849K(166016K)],
4.8204928 secs] [Times: user=4.80 sys=0.02, real=4.81 secs]
```
类似的因老年代剩余空间过小导致的对象在老年代分配失败或者**“promotion failures”**也会触发频繁的**Full GC**。  
这种问题的解决方案就是根据应用平均使用空间的大小合理配置JVM堆的大小。

## 4、JVM的bug
JVM的一些bug也会引起GC长时间停顿，比如下面列出的一些JVM bug就有可能造成Java程序长时间的GC停顿：  

- 6459113: [CMS+ParNew: wildly different ParNew
pause times depending on heap shape caused by allocation spread](http://bugs.java.com/bugdatabase/view_bug.do?bug_id=6459113)  
-- fixed in JDK 6u1 and 7  
- 6572569: [CMS: consistently skewed work
distribution indicated in (long) re-mark pauses](http://bugs.java.com/view_bug.do?bug_id=6572569)  
-- fixed in JDK 6u4 and 7
- 6631166: [CMS: better heuristics when combatting
fragmentation](http://bugs.java.com/view_bug.do?bug_id=6631166)  
-- fixed in JDK 6u21 and 7
- 6999988: [CMS: Increased fragmentation leading to
promotion failure after CR#6631166 got implemented](http://bugs.java.com/view_bug.do?bug_id=6999988)  
-- fixed in JDK 6u25 and 7
- 6683623: [G1: use logarithmic BOT code such as
used by other collectors](http://bugs.java.com/view_bug.do?bug_id=6683623)  
-- fixed in JDK 6u14 and 7
- 6976350: [G1: deal with fragmentation while
	copying objects during GC](http://bugs.java.com/view_bug.do?bug_id=6976350)  
-- fixed in JDK 8  

如果你正在运行的JVM版本包含以上bug，请升级到其他修复版本。

## 5、显示调用系统GC
请检查一下是否有显示调用系统GC的操作。应用程序或者第三方模块的某些类对`System.gc()`方法的调用会引起**stop-the-world**的**Full GC**操作。这些显示的系统GC调用也会造成长时间停顿。  

```
164638.058: [Full GC (System) [PSYoungGen: 22789K->0K(992448K)]
[PSOldGen: 1645508K->1666990K(2097152K)] 1668298K->1666990K(3089600K)
[PSPermGen: 164914K->164914K(166720K)], 5.7499132 secs] [Times: user=5.69
sys=0.06, real=5.75 secs]
```
如果你正在使用RMI框架，并观察到有固定频率的**Full GC**发生，那么就是这些RMI框架的实现类在定时触发`System.gc()`方法调用。触发间隔可以通过下面两个系统配置项配置：  

```
-Dsun.rmi.dgc.server.gcInterval=n
-Dsun.rmi.dgc.client.gcInterval=n

```
这两个配置项的默认值在JDK 1.4.2版本和5.0版本是60s，在JDK 6及以后的版本中，都是1小时。如果你想禁止因`System.gc()`方法调用引起的**Full GC**操作，可以在应用运行时增加这个`-XX:+DisableExplicitGC`JVM参数。

## 如何解决这个问题
1、收集GC日志时配置上这些参数：`-XX:+PrintGCDetails -XX:+PrintHeapAtGC
-XX:+PrintGCTimeStamps -XX:+PrintGCDateStamps -XX:+PrintGCApplicationStoppedTime`。如果是CMS收集器，再增加`-XX:PrintFLSStatistics=2`这个配置。  
这些GC日志可以给我们有关GC停顿的频率和性质的详细信息，即他们可以告诉我们GC停顿是发生在新生代还是老年代，以及停顿发生的频率等相关信息。  

2、用Solaris和其他Linux平台上的**‘iostat’**、**‘netstat’**、**‘mpstat’**、**‘vmstat’**等系统工具或者Windows操作系统上的[进程监视器](https://technet.microsoft.com/en-us/sysinternals/bb896645.aspx)和任务管理器从全局监控系统的运行情况。  

3、使用[GCHisto](https://github.com/jewes/gchisto)工具使GC日志可视化，发现哪些GC操作耗时较长，并发现这些GC的一些共性。  

4、通过GC日志发现是否在Java堆中存在碎片。  

5、监控配置的Java 堆空间是否够应用程序使用。  

6、检查你的应用程序是否运行在包含已知的会导致GC长时间停顿的bug的JVM版本。如果是，就升级到新的修复版本。