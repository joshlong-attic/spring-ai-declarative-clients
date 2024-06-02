package ai.demo;

import ai.proxy.ChatExchange;
import ai.proxy.ChatModelServiceProxyFactory;
import ai.proxy.UserParam;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
public class AiServiceProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceProxyApplication.class, args);
    }

    @Bean
    ApplicationRunner demo(ChatModel chatModel ) {
        return args -> {
            var ai = ChatModelServiceProxyFactory
                    .create(chatModel)
//                    .advisors(new QuestionAnswerAdvisor())
//                    .functions("aFunction")
                    .build();
            var client = (ImdbClient) ai.createClient(ImdbClient.class);
            var actorsWhoAppearedIn = client.findActorsWhoAppearedIn("Star Wars");
            for (var a : actorsWhoAppearedIn.actors())
                System.out.println(a.toString());

        };
    }
}

interface ImdbClient {

    @ChatExchange(user = """
             list all the actors in the movie {movie}.
            """)
     Actors findActorsWhoAppearedIn(@UserParam ("movie") String movie);
}

record Actors (List<Actor> actors ){}

record Actor(String name ) {
}
