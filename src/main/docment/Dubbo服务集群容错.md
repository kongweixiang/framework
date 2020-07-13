#Dubbo服务集群容错
集群容错包含四个部分，分别是服务目录 Directory、服务路由 Router、集群 Cluster 和负载均衡 LoadBalance。  
##服务目录 Directory
服务目录中存储了一些和服务提供者有关的信息，通过服务目录，服务消费者可获取到服务提供者的信息，比如 ip、端口、服务协议等。通过这些信息，服务消费者就可通过 Netty 等客户端进行远程调用。在一个服务集群中，服务提供者数量并不是一成不变的，如果集群中新增了一台机器，相应地在服务目录中就要新增一条服务提供者记录。或者，如果服务提供者的配置修改了，服务目录中的记录也要做相应的更新。  
服务目录目前内置的实现有两个，分别为 StaticDirectory 和 RegistryDirectory，它们均是 AbstractDirectory 的子类。
服务目录入口
```java
public List<Invoker<T>> list(Invocation invocation) throws RpcException {
        if (destroyed) {
            throw new RpcException("Directory already destroyed .url: " + getUrl());
        }

        return doList(invocation);//调用 子类doList 获取 Invoker 列表
    }
```
下边我们分别看一下StaticDirectory和RegistryDirectory的`doList`实现：

```java
//StaticDirectory.java
protected List<Invoker<T>> doList(Invocation invocation) throws RpcException {
        List<Invoker<T>> finalInvokers = invokers;
        if (routerChain != null) {
            try {
                //调用路由规则获取Invoker
                finalInvokers = routerChain.route(getConsumerUrl(), invocation);
            } catch (Throwable t) {
                logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
            }
        }
        return finalInvokers == null ? Collections.emptyList() : finalInvokers;
    }

//RegistryDirectory.java
public List<Invoker<T>> doList(Invocation invocation) {
        if (forbidden) {
            // 1.没有服务提供者 2. 服务被禁用
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, "No provider available from registry " +
                    getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " +
                    NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() +
                    ", please check status of providers(disabled, not registered or in blacklist).");
        }

        if (multiGroup) {
            return this.invokers == null ? Collections.emptyList() : this.invokers;
        }

        List<Invoker<T>> invokers = null;
        try {
            // 从缓存中获取Invoker，运行时才被执行
            invokers = routerChain.route(getConsumerUrl(), invocation);
        } catch (Throwable t) {
            logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
        }

        return invokers == null ? Collections.emptyList() : invokers;
    }        
```
最新的Dubbo中，目录服务做的功能主要是提供服务路由的入口，保存路由规则和发起路由选择的功能，更多的功能已经下沉到路由去做了。

##服务路由 Router
服务目录在获取 Invoker 列表的过程中，会通过 Router 进行服务路由，筛选出符合路由规则的服务提供者。  
服务路由包含一条路由规则，路由规则决定了服务消费者的调用目标，即规定了服务消费者可调用哪些服务提供者。  
Dubbo 目前提供了好几种服务路由实现，主要有条件路由 ConditionRouter、脚本路由 ScriptRouter 、标签路由 TagRouter和服务动态监听路由ListenableRouter等。
###条件规则
条件路由规则由两个条件组成，分别用于对服务消费者和提供者进行匹配，条件路由规则的格式如下：[服务消费者匹配条件] => [服务提供者匹配条件]。  
例如：host = 127.0.1.10 => host = 127.0.1.11  
表达式解析：


##集群 Cluster 

##负载均衡 LoadBalance