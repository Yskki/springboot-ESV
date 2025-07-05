#### 达人探店

##### 介绍

本项目是一个基于SpringBoot的前后端分离项目，实现了短信登录、商户查询缓存、优惠卷秒杀、附近商户、UV统计、用户签到、好友关注、达人探店等功能。

##### 项目部署

1. 这里是列表文本先将nginx-1.80-zip解压，静态页面资源都在里面了，双击nginx.exe文件（弹出一个小黑框（闪一下））代表静态资源启动成功。
2. 运行列表文件中的exv.sql文件创建相应的数据库表。
3. 部署Redis，修改application.yml里面的mysql地址、redis地址。
4. 启动项目后，在浏览器访问：http://localhost:8081/shop-type/list,如果可以看到数据则证明运行没有问题。

##### 项目整体架构

后端框架：SpringBoot

数据库：MySQL

缓存：Redis

Web服务器：Tomcat集群

工具库：Hutool、MyBatis

反向代理与负载均衡：Nginx
