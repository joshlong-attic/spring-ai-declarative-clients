package ai.proxy;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;


/**
 * @author Josh Long
 */
public class ChatModelServiceProxyFactory {

    //    private final StringValueResolver embeddedValueResolver;
    private final ChatModel chatModel;

    // todo make this private and replace it with a builder a la HttpServiceProxyFactory
    public ChatModelServiceProxyFactory(ChatModel model) {
//        this.embeddedValueResolver = embeddedValueResolver;
        this.chatModel = model;
    }

    private boolean isExchangeMethod(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, ChatExchange.class);
    }

    private <S> ChatModelServiceMethod createHttpServiceMethod(Class<S> serviceType, Method method) {
        return new ChatModelServiceMethod(this.chatModel, serviceType, method);
    }

    public <S> S createClient(Class<S> serviceType) {
        var httpServiceMethods =
                MethodIntrospector.selectMethods(serviceType, this::isExchangeMethod)
                        .stream()
                        .map(method -> createHttpServiceMethod(serviceType, method))
                        .toList();
        return ProxyFactory.getProxy(serviceType,
                new ChatModelServiceMethodInterceptor(httpServiceMethods));
    }


}


