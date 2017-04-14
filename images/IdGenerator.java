package com.pingan.smp.core.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Calendar;

/**
 * Created by zuodejun595 on 4/7/17.

 * 方法:uniqueId()
 * 返回参数: 分布式唯一 ID, 长度不超过 18位, long 类型,保证34年内不重复(从2017-01-01 00:00:00 算起).
 * ID 组成: 自增序列 10位, MAC 地址 5位, hostId 8位(hostId 默认采用当前主机IP第四段,支持用户自定义), 时间戳40位,可以保证34年内不重复(从2017-01-01 00:00:00 起).
 *
 * 方法: uniqueIdCurDay()
 * 返回参数: 分布式唯一ID, 长度不超过 14位, long 类型,保证当天内不重复(从每天 00:00:00 算起).
 * 一天内不重复 ID 组成: 自增序列 10位, hostId 8位(hostId 默认采用当前主机IP第四段,支持用户自定义), 时间戳27位,可以保证一天内不重复(从每天 00:00:00 算起).
 *
 * 注意事项: uniqueIdCurDay() 方法中 分布式ID 生成算法 依赖hostId,由于hostId 默认采用IP的第四段,即当应用服务器集群 跨网段部署或者数量超过256台时,可能会导致生成的分布式ID重复.
 *          uniqueId() 方法中分布式ID 生成算法依赖hostId和MAC地址对32取余, 所以当应用服务器集群 跨网段部署或者数量超过256台时,生成ID重复的概率要比 uniqueIdCurDay() 方法低.
 *          总之,当应用服务器集群 跨网段部署或者数量超过256台时,不建议继续使用该方法生成分布式唯一ID.
 *
 */
public enum IdGenerator {
    NEXT;
    private Log log = LogFactory.getLog(IdGenerator.class);
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

    /**
     * 以下为生成一天内不重复ID 所需变量
     */
    private long lastTimetampCurDay = -1L;

    private long sequenceCurDay = 0L;

    private final long hostIdShiftCurDay = sequenceBits;

    private final long timetampShiftCurDay = hostIdBits + sequenceBits;


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


    public synchronized long uniqueIdCurDay()  {
        long timetamp = timeGenCurDay();
        if (timetamp == lastTimetampCurDay) {
            sequenceCurDay = (sequenceCurDay + 1) & sequenceMask;
            if (sequenceCurDay == 0)    {
                tilNextMilsecondCurDay(lastTimetampCurDay);
            }
        }   else {
            sequenceCurDay = 0;
        }
        lastTimetampCurDay = timetamp;
        return (lastTimetampCurDay<<timetampShiftCurDay) | (hostId << hostIdShiftCurDay) | sequence;
    }

    /**
     * 返回当前时间距离距离当天凌晨00:00:00的毫秒数
     * @return
     */
    private long timeGenCurDay() {
        Calendar calendar = Calendar.getInstance();
        long currentTime = calendar.getTime().getTime();
        calendar.set(Calendar.HOUR_OF_DAY,0);
        calendar.set(Calendar.MINUTE,0);
        calendar.set(Calendar.SECOND,0);
        calendar.set(Calendar.MILLISECOND,0);
        return (currentTime - calendar.getTime().getTime());
    }

    private long tilNextMilsecondCurDay(long lastTimetampCurDay) {
        long timetamp = timeGenCurDay();
        while (timetamp <= lastTimetampCurDay)  {
            timetamp = timeGenCurDay();
        }
        return timetamp;
    }

    /**
     * 获取当前服务器Mac地址组成的字符串的hashCode
     * @return
     */
    private long getLocalMacHashCode()   {
        InetAddress ia = getnetAddress();
        //获取网卡，获取地址
        byte[] mac = new byte[0];
        try {
            mac = NetworkInterface.getByInetAddress(ia).getHardwareAddress();
        } catch (SocketException e) {
            e.printStackTrace();
            throw new RuntimeException("IdWorker get host mac address has exception!",e);
        }
        StringBuffer sb = new StringBuffer("");
        for(int i=0; i<mac.length; i++) {
            if(i!=0) {
                sb.append("-");
            }
            //字节转换为整数
            int temp = mac[i]&0xff;
            String str = Integer.toHexString(temp);
            if(str.length()==1) {
                sb.append("0"+str);
            }else {
                sb.append(str);
            }
        }
        log.info("Local host mac address:"+sb.toString().toUpperCase());
        return Math.abs(sb.toString().toUpperCase().hashCode());
    }

    /**
     * 获取当前服务器IP的最后一段
     * @return
     */
    private long getLocalIpLastSegment() {
        InetAddress ia = getnetAddress();
        String ip = ia.getHostAddress();
        String hostIpSuffix = (ip.split("\\."))[3];
        return Long.parseLong(hostIpSuffix);
    }

    /**
     * 获取当前服务器信息
     * @return
     */
    private InetAddress getnetAddress() {
        InetAddress ia = null;
        try {
            ia = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            e.printStackTrace();
            log.error("IdWorker get local host info has exception:",e);
            throw new RuntimeException("IdWorker get local host info has exception!",e);
        }
        return ia;
    }

    public static void main(String[] args) {
        int i=0;
        while (i++ < 1000)    {
            System.out.println(IdGenerator.NEXT.uniqueId());    //产生分布式唯一ID
            System.out.println(IdGenerator.NEXT.uniqueIdCurDay());  //产生当天内不重复分布式唯一ID
        }

    }
}
