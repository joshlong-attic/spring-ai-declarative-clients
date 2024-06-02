package ai.proxy;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.ReflectiveMethodInvocation;
import org.springframework.core.KotlinDetector;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.*;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * @author Josh Long
 */
public class ChatModelServiceProxyFactory {

    private final StringValueResolver embeddedValueResolver;
    private final ChatModel chatModel;

    // todo make this private and replace it with a builder a la HttpServiceProxyFactory
    public ChatModelServiceProxyFactory(ChatModel model, @Nullable StringValueResolver embeddedValueResolver) {
        this.embeddedValueResolver = embeddedValueResolver;
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


/**
 * {@link MethodInterceptor} that invokes an {@link ChatModelServiceMethod}.
 */
class ChatModelServiceMethodInterceptor implements MethodInterceptor {

    private final Map<Method, ChatModelServiceMethod> serviceMethods;

    ChatModelServiceMethodInterceptor(List<ChatModelServiceMethod> methods) {
        this.serviceMethods = methods//
                .stream()//
                .collect(Collectors.toMap(ChatModelServiceMethod::getMethod, Function.identity()));
    }

    @Override
    @Nullable
    public Object invoke(MethodInvocation invocation) throws Throwable {
        var method = invocation.getMethod();
        var serviceMethod = this.serviceMethods.get(method);
        if (serviceMethod != null) {
            var arguments = KotlinDetector//
                    .isSuspendingFunction(method) ?//
                    resolveCoroutinesArguments(invocation.getArguments()) : invocation.getArguments();
            return serviceMethod.invoke(arguments);
        }
        if (method.isDefault()) {
            if (invocation instanceof ReflectiveMethodInvocation reflectiveMethodInvocation) {
                var proxy = reflectiveMethodInvocation.getProxy();
                return InvocationHandler.invokeDefault(proxy, method, invocation.getArguments());
            }
        }
        throw new IllegalStateException("Unexpected method invocation: " + method);
    }

    private static Object[] resolveCoroutinesArguments(Object[] args) {
        var functionArgs = new Object[args.length - 1];
        System.arraycopy(args, 0, functionArgs, 0, args.length - 1);
        return functionArgs;
    }

}


class ChatModelServiceMethod {

    private final ChatModel chatModel;
    private final Class<?> containingClass;
    private final Method method;

    <S> ChatModelServiceMethod(ChatModel chatModel,
                               Class<?> containingClass,
                               Method method) {
        this.chatModel = chatModel;
        this.containingClass = containingClass;
        this.method = method;
    }

    public Method getMethod() {
        return this.method;
    }

    private static String pickHigherPriorityValueFromAnnotation(ChatExchange type, ChatExchange method, Function<ChatExchange, String> mapper) {
        var t = type == null ? null : mapper.apply(type);
        var m = method == null ? null : mapper.apply(method);
        if (StringUtils.hasText(m)) return m;
        if (StringUtils.hasText(t)) return t;
        return null;
    }

    private static ChatExchange getChatExchangeFor(List<AnnotationDescriptor> methods) {
        if (methods == null || methods.isEmpty())
            return null;
        var first = methods.getFirst();
        return first.exchange;
    }

    @Nullable
    public Object invoke(Object[] arguments) {
        var typeExchange = getChatExchangeFor(AnnotationDescriptor.getAnnotationDescriptors(this.containingClass));
        var methodExchange = getChatExchangeFor(AnnotationDescriptor.getAnnotationDescriptors(this.method));
        var user = pickHigherPriorityValueFromAnnotation(typeExchange, methodExchange, ChatExchange::user);
        var system = pickHigherPriorityValueFromAnnotation(typeExchange, methodExchange, ChatExchange::system);

        Assert.state(this.method.getParameterCount() == arguments.length, "the arguments must match the parameter count");

        var userParams = getParamsFor(this.method, UserParam.class, arguments);
        var systemParams = getParamsFor(this.method, SystemParam.class, arguments);

        var ccb = ChatClient
                .create(this.chatModel)
                .prompt();

        if (StringUtils.hasText(user))
            ccb = ccb.user(spec -> spec.text(user).params(userParams));

        if (StringUtils.hasText(system))
            ccb = ccb.system(spec -> spec.text(system).params(systemParams));

        var collection = method.getReturnType()
                .isAssignableFrom(Collection.class);
        System.out.println("is a collection? " + collection);
        return ccb.call().entity(method.getReturnType());
    }


    private static Map<String, Object> getParamsFor(Method method, Class<? extends Annotation> annotation, Object[] arguments) {
        var mapOfParams = new HashMap<String, Object>();
        var ctr = 0;
        for (var param : method.getParameters()) {
            if (param.isAnnotationPresent(annotation)) {
                var paramName = param.getName();
                var arg = arguments[ctr];
                mapOfParams.put(paramName, arg);
                ctr += 1;
            }
        }
        return mapOfParams;
    }


    private static class AnnotationDescriptor {

        static List<AnnotationDescriptor> getAnnotationDescriptors(AnnotatedElement element) {
            return MergedAnnotations//
                    .from(element, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY, RepeatableContainers.none())//
                    .stream(ChatExchange.class)//
                    .filter(MergedAnnotationPredicates.firstRunOf(MergedAnnotation::getAggregateIndex))//
                    .map(AnnotationDescriptor::new)//
                    .distinct()//
                    .toList();
        }


        private final ChatExchange exchange;
        private final MergedAnnotation<?> root;

        ChatExchange exchange() {
            return this.exchange;
        }

        AnnotationDescriptor(MergedAnnotation<ChatExchange> mergedAnnotation) {
            this.exchange = mergedAnnotation.synthesize();
            this.root = mergedAnnotation.getRoot();
        }

        @Override
        public boolean equals(Object obj) {
            return (obj instanceof AnnotationDescriptor that && this.exchange.equals(that.exchange));
        }

        @Override
        public int hashCode() {
            return this.exchange.hashCode();
        }

        @Override
        public String toString() {
            return this.root.synthesize().toString();
        }
    }

}

