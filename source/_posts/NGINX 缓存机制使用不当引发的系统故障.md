---
title: NGINX 缓存机制使用不当引发的系统故障
date: 2018-02-26 21:09:33
tags: NGINX/Openresty

---

## Shared_dict 共享内存数据丢失

近期突然发生一起很久之前上线的 NGINX lua 功能出现了空指针异常引发的系统故障，该 ngx_lua 模块的功能是统计 NGINX 接收到的 upstream 模块返回的异常请求信息，如果某个接口异常响应次数太多，则就执行对应的过载限流策略。这个功能已上线半年有余，且发生故障时确认过相关配置都没有任何修改。



Lua 功能逻辑如下：

```lua
--只统计400以上的返回异常
if status >= 400 then --请求发生异常，HTTP code > 400
	count= shared_dict:get(cur_uri)
    if count == nil then
1、		shared_dict:set(cur_uri,1)
2、		shared_dict:set(uri_last_decay_time_key,os_cur_time)
		count = 1
	else
	--获取当前异常次数的值
3、		count = shared_dict:incr(cur_uri,1)  --异常次数加 1
	end
4、	if count >= tonumber(max_fail_time) then  -- 如果异常次数超过阀值，则将该请求加入到黑名单中，执行过载策略。
		ngx.log(ngx.ERR, "nginx current request uri:", uri," fail time is up to the max_fail_time,and will be added to black_list.")
		shared_dict:set(black_list_key,1,black_list_time) -- 将该请求加入异常黑名单
5、		shared_dict:delete(cur_uri)  -- 请求加入黑名单后，会将 count 的值清空。
6、		shared_dict:delete(uri_last_decay_time_key)  -- 然后也会将最近一次衰减时间点 last_decay_time的值清空。
		return
	end
7、	local time = shared_dict:get(uri_last_decay_time_key)-- 获取最近一次衰减时间 last_decay_time，由于 last_decay_time的值被清空，所以此处取的time 值也是空。
8、	time = os_cur_time-time  -- 由于time的值为空，导致在进行算术运算时，报空指针异常。异常信息见下文。
	if time >= tonumber(decay_time) then
		--math.ceil()函数用户取整
9、		count = math.ceil(count/2)  -- 由于上面算术运算异常，导致这里一直不会执行，即count的值一直不会衰减，因此也会一直累加。知道达到阀值，被加入黑名单。
		ngx.log(ngx.DEBUG, "nginx current request uri:", uri," fail time was be decayed,current count:", count)
		shared_dict:set(cur_uri,count)
		shared_dict:set(uri_last_decay_time_key,os_cur_time)
	end
end
```

Lua 代码功能逻辑解析：

1. Nginx lua 逻辑会统计请求的异常次数，每异常一次，计数器 count 就会加 1。然后会判断 count 是否大于阀值。
2. 当请求异常次数 count 大于阀值时，就会将请求加入到黑名单中，然后当请求再次进来时，就会执行我们自定义的过载限流逻辑。
3. 如果请求异常次数 count 小于阀值，就会判断请求异常次数 count 是否达到衰减时间。如果达到衰减时间，就会执行 count = count/2 操作。
4. 当请求再次异常时，重复 1 的操作。



如下是异常日志：

```verilog
2018/02/11 00:06:50 [error] 796#796: *9925128 failed to run log_by_lua*: /wls/appsystems/lua-module/request-statistics.lua:75: attempt to perform arithmetic on local 'time' (a nil value)
stack traceback:
/wls/appsystems/lua-module/request-statistics.lua:75: in function </wls/appsystems/lua-module/request-statistics.lua:1> while logging request, client: 172.30.17.106, server: nginx_servername_http, request: "POST /do/userLogin/checkSecToken HTTP/1.1", upstream: "http://127.0.0.1:8080/do/userLogin/checkSecToken", host: "mock.com.cn:80"
```

异常日志显示的是 “`local time = shared_dict:get(uri_last_decay_time_key)`” 应用从共享内存取到的内容为空。从代码逻辑来看，不可能会发生空指针的异常，如果执行到**代码标记7处**，该 key 对应共享内存中的 value 一定不为空。既然流程没有问题，那问题就可能是出现在代码所使用的 API 上，所以自己又从头阅读了一下NGINX 共享内存的官方文档，直到读到下面这段时才发现问题所在：

[官网描述：](https://github.com/openresty/lua-nginx-module#ngxshareddictset)

“When it fails to allocate memory for the current key-value item, then `set` will try removing existing items in the storage according to the Least-Recently Used (LRU) algorithm. Note that, LRU takes priority over expiration time here. If up to tens of existing items have been removed and the storage left is still insufficient (either due to the total capacity limit specified by [lua_shared_dict](https://github.com/openresty/lua-nginx-module#lua_shared_dict) or memory segmentation), then the `err` return value will be `no memory` and `success` will be `false`.”

大意是说：当共享内存空间不足或者内存碎片太多、不足以为新对象分配空间时，会基于 LRU 算法清除掉部分元素，不管这些元素是否已经过期，并且在清除这些元素时，可能也不会有任何异常结果返回。而自己在使用共享内存时，一直把它当成本地 Redis 来使用，即内存不足时，会有提示告警（公司内部针对 Redis 服务监控发出的告警，Redis 本身并没有该功能），所以自己能提前发现问题。基于这个原因，本次异常就似乎可以解释了：

1. 当一个请求初次异常被 Nginx 统计时，会在 Nginx 的共享内存中，将 count 初始化为 1，last_decay_time 初始化为当前系统时间。所以，两个值都是非空（参考代码1、2两处）。
2. 但是 Nginx 的共享内存有个特性：当共享内存空间紧张时，会通过 LRU 算法，清除掉一些最近未使用元素，不管这些元素是否过期（Note that, LRU takes priority over expiration time here. ）。
3. 特殊情况下，count 和 last_decay_time 都可能会被清楚掉，如果count被清除掉，Nginx 会在下次请求异常时重新初始化，功能不受影响（参考代码1、2两处）；但是如果last_decay_time被清除掉后，会导致程序报上述空指针异常，并且count 值永远不会衰减，只会一直递增（参考代码7、8、9三处）。
4. 因此，只要某个请求持续偶尔有异常，并且程序运行时间足够长，count 的值一定会持续累加，直到超过阀值（参考代码3、4两处），然后被执行过载策略，误触发了系统异常。



## 问题复现

为了复现生产问题，自己做了如下本地测试：

为模拟 Nginx 共享内存 LRU 清理机制，下面代码在运行之初，首先在共享内存中设置几个 key，然后往共享内存中随机添加10000个元素，然后再取之前设置key 的值发现，这些值都为空，即已被清楚。

```lua
-- 设置初始值
limit_req_store:set("testa","a")
limit_req_store:set("testb","b")
limit_req_store:set("testc","c")

-- 向共享内存中添加任意元素
for i=1,10000,1 do
	local os_cur_time = os.time()
	local succ, err, forcible = limit_req_store:set(os_cur_time..":"..i,1)
	if forcible then
		ngx.log(ngx.ERR,"forcible=",forcible)
	end
end

-- 然后取之前设置的 key 的值
local testa = limit_req_store:get("testa")
local testb = limit_req_store:get("testb")
local testc = limit_req_store:get("testc")

-- 当任意初始设置的值丢失时，输出对应日志
if (testa ==nil or testb ==nil or testc ==nil) then
	ngx.log(ngx.ERR,"testa=",testa, ", testb=",testb,", testc=",testc)
end
```

日志输出：

```
2018/02/28 20:15:53 [error] 4587#0: *1 [lua] request-statistics.lua:21: testa=nil, testb=nil, testc=nil while logging request, client: 127.0.0.1, server: localhost, request: "POST /smp_portal HTTP/1.1", upstream: "<http://127.0.0.1:8080/test>", host: "localhost:80”
```

不过需要注意的是，由于对官方文档这句话 “**either due to the total capacity limit specified by [lua_shared_dict](https://github.com/openresty/lua-nginx-module#lua_shared_dict) or memory segmentation**” 的误解，我最初以为当 NGINX 实例内存空间不足时，就算共享内存的使用没有超过最初申请的大小时，也会因为整体空间不足而清除部分元素，后来和 Openresty 官方的维护人员讨论时才理解清楚：共享内存是 master 进程统一提前分配好的，空间大小不会因为主机内存不足而动态变化。如果主机内存空间不够，操作系统内核会负责做 page in page out 来扩展应用程序使用的空间。



## Proxy_cache 引发的异常结果缓存

自己在使用 NGINX 的 proxy_cache 时也引发过一次故障：公司搞促销活动，为提高系统响应，我就将个别配置类接口按接口级别粒度配置了 proxy_cache 缓存，在缓存有效内，所有请求都在 NGINX 直接返回，只有缓存失效才回溯到 upstream 服务获取最新结果。使用了 proxy_cache 后，系统 QPS 大幅提高，不过就在大促活动的前几天，部分配置了 proxy_cache 的接口在某些时间段出现大量异常响应。最初怀疑是 upstream 服务出现了异常，但是仔细分析后发现后台服务基本都正常。最后才定位的问题所在：使用 proxy_cache 时，NGINX 不管 upstream 服务返回的结果是什么，只有响应是“200” 或 “304”就会缓存对应的结果。特殊情况下，NGINX 缓存失效，请求达到后台服务，而恰好后台服务又因数据库、网络等原因没有正常处理请求，虽然 HTTP code 是正确的，但是包含了业务信息的响应体却是错误的。NGINX 就缓存了错误的结果，那么在接下来一段时间内，所有请求到这个 NGINX 实例上的请求返回的都是异常结果。最终优化了缓存方案，才解决这个问题。

```properties
http {
    ...
    proxy_cache_path /wls/appsystem/proxy_cache levels=1:2 keys_zone=melco:500m inactive=30m max_size=1000m use_temp_path=off; #proxy_cache 初始化配置
    ...
    server {
        listen       80;
        ...

        # NGINX proxy_cache 配置示例
        location ^~ /littleRedDot/getLittleRedDot {
            proxy_http_version 1.1;
            proxy_set_header Connection "";

            #proxy_cache 配置
            proxy_cache melco;
            proxy_cache_methods GET HEAD POST;
            proxy_cache_key $request_uri;
            proxy_cache_valid 200 304 30m;
            proxy_ignore_headers Set-Cookie;
            proxy_hide_header Set-Cookie;
            #proxy_cache 配置结束

            proxy_set_header Host $host;
            proxy_set_header X-Forwarded-Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-Server $host;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

            proxy_pass http://backends;
        }
```

