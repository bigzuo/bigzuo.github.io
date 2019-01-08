---
title: Twitter snowflake 分布式ID生成算法的简单改造
date: 2017-04-14 20:03:37
categories: 算法
---

> 原创文章，如需转载，请注明来自：<https://bigzuo.github.io/>

## 背景介绍
项目开发过程中经常会需要生成一个唯一ID，并且由于现在项目应用都是集群部署，所以更多时候还需要保证唯一ID支持分布式。  
一些情况下，开发人员为了省事，会直接使用时间戳`System.currentTimeMillis()`
或者是**UUID**。这两种方式都存在不完善的地方。尤其是采用时间戳，时间戳本质是毫秒数，完全不支持分布式，会存在很高可能导致生成的ID重复。**UUID**虽然本身生成机制可以保证全球唯一，横向扩展性好，但是部分场景下，**UUID**长度可能过长，并且**UUID**是字符串类型，对需要对ID进行排序、对比的场景支持的并不友好，索引效率也很低。  
鉴于以上原因，我们基于*Twitter* 开源的分布式ID生成算法**Snowflake**重写了一套分布式ID生成算法，即可以保证分布式唯一，又可以解决采用时间戳或者**UUID**带来的问题。

<!-- more -->

## 算法详解
**Snowflake**是*Twitter* 开源的一个分布式ID生成算法，目前使用范围很广。其核心思想是将一个**long**类型的数字分成4段，其中毫秒内自增序列占12位，机器ID占5位，机房ID占5位，时间戳占41位，最高位为符号位，然后组合生成一个64位**long**类型的分布式唯一ID。ID长度不超过18位，并且长时间内趋势递增，方便分析、统计和排序。这个算法理论上可以保证在69年内、1024台机器上生成的ID唯一。  
我们结合项目组的实际情况，即一般一个应用部署的机器集群不会超过100台，并且一般都部署在同一机房，做了如下改造：
![](https://bigzuo.github.io/images/distribute_unique_id.png)  

- ID生成规则重组。毫秒内自增序列占10位，机器ID占8位，默认采用服务器IP的第四段，机器MAC地址hoshCode占5位，时间戳占40位（见上图）。这样就可以保证在同一机房、同一网段的256台机器生成的ID唯一。
- 新增一个ID生成算法：`uniqueIdCurDay()`，可以生成一天内不重复的分布式唯一ID，并且长度不超过14位。因为在和关联系统交互中，经常关联方会要求生成一个一天内不重复的ID，并且长度不超过15位。  

经过改造后，**新的算法可以支持同一机房内的256台机器34年内生成的ID不重复，并且每台机器每秒可以生成100万个ID**，基本可以满足项目组的需要，并且线程安全。  


核心代码：  

```java
private final long twepoch = 1482192000000L; //twepoch 为System.currentTimeMillis() 方法起始时间1970-1-01 00:00:00.000 到 2017-01-01 00:00:00.000 之间的毫秒数.
    private long sequence = 0L;

    private long hostId = 0L;
    private long hostMac = 0L;

    private final long sequenceBits = 10L;
    private final long sequenceMask = -1L^(-1L<<sequenceBits);

    private final long hostIdBits = 8L;
    private final long hostMacBits = 5L;

    private final long hostMacMax = -1L^(-1L<<hostMacBits);
    private final long hostIdMax = -1L^(-1L<< hostIdBits);

    private final long hostMacShift = sequenceBits;
    private final long hostIdShift = hostMacBits+sequenceBits;
    private final long timetampShift = hostIdBits + hostMacBits+sequenceBits;

    private long lastTimetamp = -1L;

    private IdGenerator() {
        hostId = getLocalIpLastSegment();
        hostMac = getLocalMacHashCode()%hostMacMax;
    }

    private IdGenerator(long hostId) {
        if (hostId <0 || hostId > hostIdMax)    {
            throw new RuntimeException(String.format("Host Id can't be greater than %d or less than 0",hostIdMax));
        }
        this.hostId = hostId;
        hostMac = getLocalMacHashCode()%hostMacMax;
    }

    public synchronized long uniqueId()   {
        long currentTime = timeGen();
        if (currentTime < lastTimetamp) {
            log.error(String.format("Clock is moving backwards.  Rejecting requests until %d.", lastTimetamp));
            throw new RuntimeException(String.format("Clock moved backwards, Refusing to generate id for %d milliseconds.",(lastTimetamp - currentTime)));
        }
        if (currentTime == lastTimetamp)    {
            sequence = (sequence+1) & sequenceMask;
            if (sequence ==0 )  {
                tilNextMilsecond(lastTimetamp);
            }
        }   else {
            sequence = 0L;
        }
        lastTimetamp = currentTime;
        return ((lastTimetamp - twepoch)<<timetampShift) | (hostId << hostIdShift) | (hostMac << hostMacShift) | sequence;
    }

    private long timeGen()  {
        return System.currentTimeMillis();
    }

    private long tilNextMilsecond(long lastTimetamp) {
        long timetamp = timeGen();
        while (timetamp <= lastTimetamp)  {
            timetamp = timeGen();
        }
        return timetamp;
    } 
```

完整代码下载：[IdGenerator.java](https://bigzuo.github.io/images/IdGenerator.java)  
引用方法非常简单： 
 
```java
System.out.println(IdGenerator.NEXT.uniqueId());    //产生分布式唯一ID
System.out.println(IdGenerator.NEXT.uniqueIdCurDay());  //产生当天内不重复分布式唯一ID
```

## 注意事项
`uniqueIdCurDay()` 方法中 分布式ID 生成算法 依赖hostId，由于hostId 默认采用IP的第四段，即当应用服务器集群 跨网段部署或者数量超过256台时，可能会导致生成的分布式ID重复。`uniqueId()` 方法中分布式ID 生成算法依赖hostId和MAC地址对32取余， 所以当应用服务器集群 跨网段部署或者数量超过256台时，生成ID重复的概率要比 uniqueIdCurDay() 方法低很多，但是依然会有重复的可能。  

总之，当应用服务器集群 跨网段部署或者数量超过256台时,不建议继续使用该方法生成分布式唯一ID。

## 参考资料
[Twitter-Snowflake，64位自增ID算法详解](http://www.lanindex.com/twitter-snowflake%EF%BC%8C64%E4%BD%8D%E8%87%AA%E5%A2%9Eid%E7%AE%97%E6%B3%95%E8%AF%A6%E8%A7%A3/)