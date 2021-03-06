package com.alibaba.dubbo.rpc.protocol.parrot;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.AtomicPositiveInteger;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.TimeoutException;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.protocol.AbstractInvoker;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 
 * <B>Description</B> TODO <br />
 * <B>Copyright</B> Copyright (c) 2016 www.diligrp.com All rights reserved. <br />
 * 本软件源代码版权归聚美优品,未经许可不得任意复制与传播.<br />
 * <B>Company</B> 聚美优品
 * @param <T>
 * @createTime 2016年8月19日 下午5:17:29
 * @author lushiwei
 */
public class ParrotInvoker<T> extends AbstractInvoker<T> {

    private final ExchangeClient[] clients;

    private final AtomicPositiveInteger index = new AtomicPositiveInteger();

    private final ReentrantLock destroyLock = new ReentrantLock();

    private final Set<Invoker<?>> invokers;

    public ParrotInvoker(Class<T> service, URL url, ExchangeClient[] clients) {
        this(service, url, clients, null);
    }

    public ParrotInvoker(Class<T> type, URL url, ExchangeClient[] clients, Set<Invoker<?>> invokers) {
        super(type, url, new String[] { Constants.INTERFACE_KEY, Constants.GROUP_KEY, Constants.TOKEN_KEY, Constants.TIMEOUT_KEY });
        this.clients = clients;
        this.invokers = invokers;
    }

    @Override
    protected Result doInvoke(Invocation invocation) throws Throwable {

        RpcInvocation inv = (RpcInvocation) invocation;

        final String methodName;

        methodName = invocation.getMethodName();

        inv.setAttachment(Constants.PATH_KEY, getUrl().getPath());

        /*
         * // for thrift codec inv.setAttachment(
         * ThriftCodec.PARAMETER_CLASS_NAME_GENERATOR, getUrl().getParameter(
         * ThriftCodec.PARAMETER_CLASS_NAME_GENERATOR,
         * DubboClassNameGenerator.NAME ) );
         */
        ExchangeClient currentClient;

        if (clients.length == 1) {
            currentClient = clients[0];
        } else {
            currentClient = clients[index.getAndIncrement() % clients.length];
        }

        try {
            int timeout = getUrl().getMethodParameter(methodName, Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);

            RpcContext.getContext().setFuture(null);

            return (Result) currentClient.request(inv, timeout).get();

        } catch (TimeoutException e) {
            e.printStackTrace();
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, e.getMessage(), e);
        } catch (RemotingException e) {
            e.printStackTrace();
            throw new RpcException(RpcException.NETWORK_EXCEPTION, e.getMessage(), e);
        }

    }

    @Override
    public boolean isAvailable() {

        if (!super.isAvailable()) {
            return false;
        }

        for (ExchangeClient client : clients) {
            if (client.isConnected() && !client.hasAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY)) {
                // cannot write == not Available ?
                return true;
            }
        }
        return false;
    }

    public void destroy() {
        // 防止client被关闭多次.在connect per
        // jvm的情况下，client.close方法会调用计数器-1，当计数器小于等于0的情况下，才真正关闭
        if (super.isDestroyed()) {
            return;
        } else {
            // dubbo check ,避免多次关闭
            destroyLock.lock();

            try {

                if (super.isDestroyed()) {
                    return;
                }

                super.destroy();

                if (invokers != null) {
                    invokers.remove(this);
                }

                for (ExchangeClient client : clients) {

                    try {
                        client.close();
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }

                }

            } finally {
                destroyLock.unlock();
            }

        }

    }

}
